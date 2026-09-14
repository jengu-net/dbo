package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Held;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pass that loses the race for its own record does not take the work with it.
 *
 * <p>Everything a pass does to its run is a read-then-conditional-write, and
 * nothing caught the conflict — so a second writer at the wrong moment threw
 * out of the pass, past the loop that turns a refused declaration into a card,
 * and out of whatever door was asking, which answered that the application did
 * not complete. By then the declarations were applied. What was lost was the
 * account of them, and the run somebody would open to find out is the record
 * that could not be written.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARunSurvivesLosingItsOwnRecordIT {

    static PgObjectStore engine;
    static Contending store;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ARunSurvivesLosingItsOwnRecordIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.canonical("ValueSet")));
        engine = new PgObjectStore(ds, Registrations.withRuns(personality.registrations()));
        store = new Contending(engine);
    }

    private static ConfigApplication.Declared valueSet(int i) {
        return new ConfigApplication.Declared("ValueSet", "value-sets/vs-" + i + ".json",
                ("{\"resourceType\":\"ValueSet\",\"status\":\"active\",\"url\":"
                        + "\"https://race.test/vs/" + i + "\",\"name\":\"VS" + i + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Somebody writes between the read and the write, once. The advance reads
     * again and applies itself on top of what they wrote.
     */
    @Test
    @Proving(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP)
    @DisplayName("an advance that loses the race reads again and lands, rather than throwing "
            + "out of the pass that was making it")
    void anAdvanceThatLosesTheRaceTriesAgain() {
        Runs runs = new Runs(store);
        Run sweep = runs.sweep("race.test", "apply", "once");

        store.interfere(1);
        Run advanced = runs.held(sweep, cloud.jengu.dbo.work.Holder.PERSON);

        assertEquals(0, store.remaining(), "the test did not actually contend the record");
        assertEquals(cloud.jengu.dbo.work.Holder.PERSON, advanced.holder(),
                "the advance was lost rather than re-applied");
    }

    /**
     * The declarations are applied, and the bookkeeping loses every attempt.
     * The pass still answers for what it did.
     */
    @Test
    @Proving(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP)
    @DisplayName("a pass whose own record is written by somebody else on every attempt still "
            + "reports the declarations it applied")
    void aPassThatCannotCloseStillSaysWhatItApplied() {
        ConfigApplication configuration =
                new ConfigApplication(store, new Runs(store), R4Personality.DOMAIN);

        // More than the advance will ever retry, so the closing loses outright.
        store.interfere(50);
        ConfigApplication.Outcome outcome = configuration.apply("race/unclosable", null,
                List.of(valueSet(900), valueSet(901)));
        store.interfere(0);

        assertEquals(2, outcome.read(), outcome.toString());
        assertEquals(2, outcome.applied() + outcome.unchanged(),
                "the declarations were applied and the answer did not say so: " + outcome);
        // And they really are there — the point is that the work stands while
        // the account of it is behind, not that both were abandoned together.
        assertEquals(2, store.select(Criteria.of("ValueSet")).stream()
                .filter(v -> new String(v.payload(), StandardCharsets.UTF_8)
                        .contains("https://race.test/vs/90")).count(),
                "the pass reported an application that did not happen");
    }

    /** Losing every attempt is its own answer, not the engine's conflict. */
    @Test
    @Proving(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP)
    @DisplayName("an advance that never wins says what is behind, in its own words")
    void anAdvanceThatNeverWinsSaysSo() {
        Runs runs = new Runs(store);
        Run sweep = runs.sweep("race.test", "apply", "never");

        store.interfere(50);
        Runs.Contended behind = assertThrows(Runs.Contended.class,
                () -> runs.held(sweep, cloud.jengu.dbo.work.Holder.PERSON));
        store.interfere(0);

        assertTrue(behind.getMessage().contains("race.test/apply/never"),
                "a contended advance has to name the run it could not advance: "
                        + behind.getMessage());
    }

    /**
     * A store that lets somebody else write a run between the read that chose
     * a version and the write that uses it — which is the only window the
     * conflict exists in, and not one two threads can be made to hit on
     * purpose.
     */
    static final class Contending implements ObjectStore {

        private final ObjectStore inner;
        private int interfere;

        Contending(ObjectStore inner) {
            this.inner = inner;
        }

        void interfere(int times) {
            this.interfere = times;
        }

        int remaining() {
            return interfere;
        }

        @Override
        public PutResult put(PutRequest request) {
            if (interfere > 0 && WorkModel.TYPE.equals(request.typeName())
                    && request.expectedVersion() != null) {
                interfere--;
                // Somebody else advances the same record first. Rewriting what
                // is there is enough: the version moves, which is all the
                // conditional write is looking at.
                inner.get(WorkModel.TYPE, request.id()).ifPresent(current ->
                        inner.put(new PutRequest(WorkModel.TYPE, current.id(),
                                current.versionId(), current.payload())));
            }
            return inner.put(request);
        }

        @Override
        public PutResult put(PutRequest request, Handling.Authority caller) {
            return inner.put(request, caller);
        }

        @Override
        public PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
            return inner.putIfAbsent(identity, request);
        }

        @Override
        public PutResult putConditional(IdentityRef identity, PutRequest request) {
            return inner.putConditional(identity, request);
        }

        @Override
        public PutResult putConditional(IdentityRef identity, PutRequest request,
                Handling.Authority caller) {
            return inner.putConditional(identity, request, caller);
        }

        @Override
        public Optional<StoredObject> get(String typeName, String id) {
            return inner.get(typeName, id);
        }

        @Override
        public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
            return inner.getByIdentifier(typeName, identifiers);
        }

        @Override
        public void delete(String typeName, String id, Long expectedVersion) {
            inner.delete(typeName, id, expectedVersion);
        }

        @Override
        public void delete(String typeName, String id, Long expectedVersion,
                Handling.Authority caller) {
            inner.delete(typeName, id, expectedVersion, caller);
        }

        @Override
        public List<StoredObject> history(String typeName, String id) {
            return inner.history(typeName, id);
        }

        @Override
        public List<StoredObject> select(Criteria criteria) {
            return inner.select(criteria);
        }

        @Override
        public long count(Criteria criteria) {
            return inner.count(criteria);
        }

        @Override
        public cloud.jengu.dbo.core.api.feed.FeedChunk<StoredObject> page(Criteria criteria,
                String cursor) {
            return inner.page(criteria, cursor);
        }

        @Override
        public List<Held> inventory(String typeName, List<String> paths) {
            return inner.inventory(typeName, paths);
        }

        @Override
        public int rebuildEnvelopes(String typeName) {
            return inner.rebuildEnvelopes(typeName);
        }

        @Override
        public TypeRegistration registrationOf(String typeName) {
            return inner.registrationOf(typeName);
        }

        @Override
        public java.util.Collection<TypeRegistration> registrations() {
            return inner.registrations();
        }

        @Override
        public int reindexUnder(TypeRegistration replacement) {
            return inner.reindexUnder(replacement);
        }
    }
}
