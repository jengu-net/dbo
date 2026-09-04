package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.sync.ConfigSource;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a declaration is a sweep, and the two it could not apply do not take
 * the other forty-four with them.
 *
 * <p>The sentence this proves — <i>read 46, applied 44, skipped 2 with
 * reasons</i> — is the one that had nowhere to live but a log line, which is why
 * a partial application presented as "my configuration had no effect" to the
 * consumer whose loader applies a zone's declarations.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigAppliesAsASweepIT {

    static PgObjectStore store;
    static Runs runs;
    static ConfigApplication configuration;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ConfigAppliesAsASweepIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.canonical("ValueSet")));
        store = new PgObjectStore(ds, Registrations.withRuns(personality.registrations()));
        runs = new Runs(store);
        configuration = new ConfigApplication(store, runs, R4Personality.DOMAIN);
    }

    private static ConfigApplication.Declared valueSet(int i) {
        return new ConfigApplication.Declared("ValueSet", "value-sets/vs-" + i + ".json",
                ("{\"resourceType\":\"ValueSet\",\"status\":\"active\",\"url\":"
                        + "\"https://zone.test/vs/" + i + "\",\"name\":\"VS" + i + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Proving(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP)
    @DisplayName("forty-six declared, forty-four applied, two cards — and a tally that says so")
    void aPartialApplicationSaysWhatItDid() {
        List<ConfigApplication.Declared> declarations = new ArrayList<>();
        for (int i = 0; i < 44; i++) {
            declarations.add(valueSet(i));
        }
        declarations.add(new ConfigApplication.Declared("Nonesuch", "value-sets/unknown-type.json",
                "{\"resourceType\":\"Nonesuch\"}".getBytes(StandardCharsets.UTF_8)));
        declarations.add(new ConfigApplication.Declared("ValueSet", "value-sets/broken.json",
                "not json at all".getBytes(StandardCharsets.UTF_8)));

        long before = store.count(Criteria.of("ValueSet"));
        ConfigApplication.Outcome outcome = configuration.apply("zone/ee", "commit:abc123",
                declarations);

        assertEquals(46, outcome.read());
        assertEquals(44, outcome.applied(), "one bad record must not take the rest of the zone");
        assertEquals(2, outcome.skipped());
        assertEquals(before + 44, store.count(Criteria.of("ValueSet")),
                "and the forty-four are actually here");

        Run sweep = runs.byKey(ConfigApplication.PROCESS + "/" + ConfigApplication.STEP
                + "/zone/ee").orElseThrow();
        assertEquals(RunKind.SWEEP, sweep.kind());
        assertEquals(44L, sweep.tally().get("applied"));
        assertEquals(46L, sweep.tally().get("read"));
        assertEquals(2L, sweep.tally().get("skipped"));
        assertEquals("commit:abc123", sweep.correlated().orElseThrow(),
                "the correlation the declaration carried, echoed and never parsed");
        assertTrue(sweep.needsAPerson(), "two declarations need somebody to change them");

        List<Run> cards = runs.items(sweep).stream().filter(Run::needsAPerson).toList();
        assertEquals(2, cards.size(), "one card per thing somebody must fix: " + cards);
        assertTrue(cards.stream().anyMatch(card ->
                        card.item().reference().equals("value-sets/broken.json")),
                "and each names the declaration by what it is called where it was written: "
                        + cards);
    }

    @Test
    @Proving({DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP,
            DboPromises.PROC_CLOSE_BY_RE_EVALUATION})
    @DisplayName("fixing a declaration closes its card on the next pass, with nobody clicking "
            + "resolved")
    void aFixedDeclarationClosesItself() {
        configuration.apply("zone/lv", null, List.of(
                new ConfigApplication.Declared("ValueSet", "value-sets/lv.json",
                        "not json at all".getBytes(StandardCharsets.UTF_8))));
        Run sweep = runs.byKey(ConfigApplication.PROCESS + "/" + ConfigApplication.STEP
                + "/zone/lv").orElseThrow();
        assertTrue(sweep.needsAPerson());

        configuration.apply("zone/lv", null, List.of(
                new ConfigApplication.Declared("ValueSet", "value-sets/lv.json",
                        ("{\"resourceType\":\"ValueSet\",\"status\":\"active\",\"url\":"
                                + "\"https://zone.test/vs/lv\",\"name\":\"LV\"}")
                                .getBytes(StandardCharsets.UTF_8))));

        Run after = runs.byKey(sweep.key()).orElseThrow();
        assertFalse(after.needsAPerson(),
                "the world agrees now, so the sweep does — a card closed by click reads "
                        + "resolved while the fault is live");
        assertTrue(runs.items(after).stream().noneMatch(item -> item.holder() == Holder.PERSON));
    }

    /** A source that reads the same twice is read twice and applied once. */
    @Test
    @Proving(DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE)
    @DisplayName("an unchanged source is a read, not a re-application")
    void anUnchangedSourceIsNotReapplied() {
        List<ConfigApplication.Declared> set = List.of(valueSet(100), valueSet(101));
        ConfigSource source = () -> new ConfigSource.Fetch(set, ConfigSource.markerOf(set));

        ConfigApplication.Outcome first = configuration.applyFrom("zone/unchanged", source);
        assertEquals(2, first.applied());

        ConfigApplication.Outcome again = configuration.applyFrom("zone/unchanged", source);
        assertEquals(0, again.read(), "the second pass had nothing to do and did it");

        Run sweep = runs.byKey(ConfigApplication.PROCESS + "/" + ConfigApplication.STEP
                + "/zone/unchanged").orElseThrow();
        assertEquals(2L, sweep.tally().get("applied"),
                "and the run still says what the pass that mattered did");
        assertEquals(ConfigSource.markerOf(set), sweep.correlated().orElseThrow(),
                "what this scope agreed with, on the record rather than in a field");
    }

    /**
     * The skip must never swallow a card. While one is open the world does not
     * agree yet, so an unchanged source is exactly what has to be re-read — it
     * is how fixing the store, rather than the declaration, closes the card.
     */
    @Test
    @Proving({DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE,
            DboPromises.PROC_CLOSE_BY_RE_EVALUATION})
    @DisplayName("an unchanged source is re-applied while a card is open")
    void anOpenCardKeepsThePassRunning() {
        List<ConfigApplication.Declared> broken = List.of(
                new ConfigApplication.Declared("ValueSet", "value-sets/held.json",
                        "not json at all".getBytes(StandardCharsets.UTF_8)));
        ConfigSource source = () ->
                new ConfigSource.Fetch(broken, ConfigSource.markerOf(broken));

        assertEquals(1, configuration.applyFrom("zone/held", source).skipped());
        assertTrue(runs.byKey(ConfigApplication.PROCESS + "/" + ConfigApplication.STEP
                + "/zone/held").orElseThrow().needsAPerson());

        ConfigApplication.Outcome again = configuration.applyFrom("zone/held", source);
        assertEquals(1, again.read(),
                "the same read was skipped while somebody still owed an answer");
    }

    /**
     * Declaring the same thing again is what a source moving forward looks
     * like, and it was the one case nothing exercised: a plain create refuses
     * an identity already claimed, so every changed declaration used to be a
     * card saying so, with the store left holding the version before it.
     */
    @Test
    @Proving(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP)
    @DisplayName("a changed declaration replaces the one on record, and is nobody's card")
    void aChangedDeclarationReplacesWhatItDeclared() {
        ConfigApplication.Declared before = new ConfigApplication.Declared("ValueSet",
                "value-sets/moving.json",
                ("{\"resourceType\":\"ValueSet\",\"status\":\"draft\",\"url\":"
                        + "\"https://zone.test/vs/moving\",\"name\":\"Moving\"}")
                        .getBytes(StandardCharsets.UTF_8));
        ConfigApplication.Declared after = new ConfigApplication.Declared("ValueSet",
                "value-sets/moving.json",
                ("{\"resourceType\":\"ValueSet\",\"status\":\"active\",\"url\":"
                        + "\"https://zone.test/vs/moving\",\"name\":\"Moving\"}")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(1, configuration.apply("zone/moving", null, List.of(before)).applied());
        long held = store.count(Criteria.of("ValueSet"));

        ConfigApplication.Outcome second =
                configuration.apply("zone/moving", null, List.of(after));

        assertEquals(1, second.applied(), "declaring it again is an application, not a refusal");
        assertEquals(0, second.skipped());
        assertEquals(held, store.count(Criteria.of("ValueSet")),
                "and it replaced the record rather than adding a second one");
        assertFalse(runs.byKey(ConfigApplication.PROCESS + "/" + ConfigApplication.STEP
                + "/zone/moving").orElseThrow().needsAPerson());
    }
}
