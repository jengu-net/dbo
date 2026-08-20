package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run is a record, so it can be seen, queried and acted on (#46).
 *
 * <p>The alternative — a private table — is visible in the sense that rows
 * exist and invisible in every sense that matters: no envelope to query it by,
 * no history, no feed, nothing in the backup, nothing dropped with the tenant.
 * This test is over a real store for that reason: the claim is about
 * registration, and a fake would prove the parts that were never in doubt.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunsAreRecordsIT {

    static Runs runs;
    static PgObjectStore store;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("RunsAreRecordsIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, WorkModel.registrations());
        runs = new Runs(store);
    }

    @Test
    @DisplayName("a run over N items where K fail keeps the tally and does not abandon the rest")
    void aRunOverItemsKeepsItsTally() {
        Run ingest = runs.pipeline("dbo.terminology.ingest", "import");
        runs.item(ingest, "CodeSystem/colours", Failure.RECORD, "concept without a code");
        runs.item(ingest, "CodeSystem/units", Failure.RECORD, "duplicate code 'mg'");
        Run tallied = runs.tally(ingest, Map.of("read", 46L, "applied", 44L, "skipped", 2L));

        assertEquals(RunKind.PIPELINE, tallied.kind());
        assertEquals(Map.of("read", 46L, "applied", 44L, "skipped", 2L), tallied.tally(),
                "the tally is the answer to 'what happened', and it had nowhere to live before");
        assertEquals(2, runs.items(tallied).size(),
                "two items failed and forty-four did not: the run does not abandon the rest");
    }

    @Test
    @DisplayName("a record that is wrong reaches a person; a store that is away is a retry")
    void escalationFollowsTheFailureClass() {
        Run delivery = runs.pipeline("dbo.subscriptions.delivery", "post");
        Run wrong = runs.item(delivery, "Subscription/one", Failure.RECORD, "endpoint rejected it");
        Run away = runs.item(delivery, "Subscription/two",
                Failure.of(new java.net.SocketTimeoutException("read timed out")), "no answer");

        assertEquals(Holder.PERSON, wrong.holder(), "a record that is wrong is somebody's job");
        assertEquals(Holder.RETRY, away.holder(),
                "a transient fault on somebody's card is how a queue becomes a graveyard");
        assertTrue(runs.holding(Holder.PERSON).stream()
                        .anyMatch(run -> run.id().equals(wrong.id())),
                "what waits for a person must be a query, not a log line");
    }

    @Test
    @DisplayName("a sweep closes what stops failing, without anybody clicking resolved")
    void aSweepClosesByReEvaluation() {
        Run sweep = runs.sweep("dbo.config.applied", "apply", "hogwarts");

        runs.pass(sweep).counted("read", 46).counted("applied", 44)
                .item("CodeSystem/one", Failure.RECORD, "malformed")
                .item("CodeSystem/two", Failure.RECORD, "malformed")
                .done();
        assertEquals(Holder.PERSON, runs.byKey(sweep.key()).orElseThrow().holder());
        assertEquals(2, openItems(sweep));

        // somebody fixes one of them in the configuration repository
        runs.pass(sweep).counted("read", 46).counted("applied", 45)
                .item("CodeSystem/two", Failure.RECORD, "malformed")
                .done();
        assertEquals(1, openItems(sweep), "what stopped failing closed itself");
        assertEquals(Holder.PERSON, runs.byKey(sweep.key()).orElseThrow().holder());

        // and then the other
        Run converged = runs.pass(sweep).counted("read", 46).counted("applied", 46).done();
        assertEquals(0, openItems(sweep));
        assertEquals(Holder.NOBODY, converged.holder(),
                "a sweep converges rather than finishing, and nobody closed it by hand");
        assertEquals(Map.of("read", 46L, "applied", 46L), converged.tally());
    }

    @Test
    @DisplayName("a large run is a tally and a handful of children, not one child per item")
    void childrenAreExceptionsNotAnEnumeration() {
        Run ingest = runs.pipeline("dbo.terminology.ingest", "import");
        // forty thousand concepts, three of which nobody can accept
        runs.item(ingest, "CodeSystem/snomed#ambiguous", Failure.RECORD, "two displays");
        runs.item(ingest, "CodeSystem/snomed#orphan", Failure.RECORD, "parent not in this system");
        runs.item(ingest, "CodeSystem/snomed#duplicate", Failure.RECORD, "code twice");
        Run counted = runs.tally(ingest, Map.of("read", 40_000L, "applied", 39_997L,
                "skipped", 3L));

        assertEquals(3, runs.items(counted).size(),
                "a child per item processed would be forty thousand records, forty thousand "
                        + "feed events, and a history nobody can page through");
        assertEquals(40_000L, counted.tally().get("read"),
                "what everything did is the tally's job; what somebody must act on is a child's");
    }

    @Test
    @DisplayName("the sweep is found again rather than started again")
    void aSweepIsOnePerScope() {
        Run first = runs.sweep("dbo.policy.retention", "sweep", "hogwarts");
        Run second = runs.sweep("dbo.policy.retention", "sweep", "hogwarts");
        Run elsewhere = runs.sweep("dbo.policy.retention", "sweep", "beauxbatons");

        assertEquals(first.id(), second.id(),
                "the operator's question is about the thing, not about the pass");
        assertFalse(first.id().equals(elsewhere.id()), "and it is per scope");
    }

    @Test
    @DisplayName("the envelope says what is waiting, and never what it was about")
    void theEnvelopeDisclosesStateNotSubject() {
        Run run = runs.pipeline("dbo.terminology.ingest", "import");
        Run item = runs.item(run, "Patient/38001010001-is-identifying", Failure.RECORD,
                "a message that names a person");

        Envelope envelope = WorkModel.registrations().get(0).extractor()
                .extract(WorkModel.TYPE, store.get(WorkModel.TYPE, item.id())
                        .orElseThrow().payload());

        assertTrue(envelope.paths().containsKey("holder")
                && envelope.paths().containsKey("step")
                && envelope.paths().containsKey("parent"),
                "state has to be queryable, or the list an operator opens cannot exist: "
                        + envelope.paths().keySet());
        assertFalse(envelope.paths().toString().contains("38001010001"),
                "an envelope is read by parties entitled to route on it and not to read "
                        + "payloads: " + envelope.paths());
        assertFalse(envelope.paths().toString().contains("names a person"),
                envelope.paths().toString());
        assertTrue(item.item().reference().contains("38001010001"),
                "the record still carries it, for whoever may open the record");
    }

    @Test
    @DisplayName("a correlation from elsewhere is echoed and never interpreted")
    void aCorrelationIsEchoed() {
        Run run = runs.correlated(runs.pipeline("dbo.config.applied", "apply"),
                "sha:9f2b1c/run:4417");

        assertEquals("sha:9f2b1c/run:4417", runs.byKey(run.key()).orElseThrow()
                .correlated().orElseThrow(),
                "the join has to be queryable from either side without that system's "
                        + "vocabulary entering the engine");
    }

    private static long openItems(Run sweep) {
        List<Run> items = runs.items(runs.byKey(sweep.key()).orElseThrow());
        return items.stream().filter(Run::open).count();
    }
}
