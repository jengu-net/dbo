package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.PolicyViolationException;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.policy.AuditModel;
import cloud.jengu.dbo.policy.PolicyObjectStore;
import cloud.jengu.dbo.policy.TenantPolicies;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.testmodel.GadgetModel;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.LaneModel;
import cloud.jengu.dbo.sync.Lanes;
import cloud.jengu.dbo.sync.PlacementModel;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replica's trail reaches the other side as the replica recorded it.
 *
 * <p>The last of the replication toolset. Everything else on the lane is
 * content, and content is written the way anything is written; audit is not —
 * direct writes to the type are refused for every caller, deliberately, and
 * a lane that quietly went around that refusal would be a caller able to
 * write somebody else's history. So there is exactly one admission, the
 * {@code AuditReplay} port, and this is what it has to be worth.
 *
 * <p>Both stores are policy-wrapped here, which is the shape the question was
 * really about: the refusal only exists on a wrapped store, and a lane tested
 * against a raw one would prove nothing about the thing that refuses.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditReplicatesAsRecordedIT {

    private static final String PROCESS = "dbo.lab.result";
    private static final String STEP = "validate";
    private static final Set<String> TRAVELS = Set.of(PROCESS);

    static PolicyObjectStore cloudStore;
    static PolicyObjectStore siteStore;
    static PgObjectStore cloudEngine;
    static PgObjectStore siteEngine;
    static Runs siteRuns;
    static Lanes cloud;
    static Lanes site;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("AuditReplicatesAsRecordedIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE trail_cloud");
            st.execute("CREATE DATABASE trail_site");
        }
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        PGSimpleDataSource cloudDs = ds(base + "trail_cloud");
        PGSimpleDataSource siteDs = ds(base + "trail_site");

        // The test personality, not a FHIR one: nothing here parses a
        // resource, and a face would only add a HAPI context and a version's
        // definitions to a suite that measures its heap in whether the next
        // test class can open a connection pool.
        List<cloud.jengu.dbo.core.api.TypeRegistration> declarations =
                new ArrayList<>(GadgetModel.registrations());
        declarations.addAll(WorkModel.registrations());
        declarations.addAll(LaneModel.registrations());
        declarations.addAll(PlacementModel.registrations());
        declarations.addAll(AuditModel.registrations());

        // WRITES rather than FULL: this is about what a write records, and
        // read auditing would put the lane's own queries in the trail it is
        // trying to replicate.
        TenantPolicies policies = TenantPolicies.parse(Map.of(
                "audit", Map.of("level", "writes")));

        cloudEngine = new PgObjectStore(cloudDs, declarations);
        siteEngine = new PgObjectStore(siteDs, declarations);
        cloudStore = new PolicyObjectStore(cloudEngine, policies);
        siteStore = new PolicyObjectStore(siteEngine, policies);
        siteRuns = new Runs(siteStore);
        // The port is held by the side that RECEIVES: the receiver admits what
        // the source recorded, and the source needs no admission to send it.
        cloud = new Lanes(cloudStore, new PgChangeFeed(cloudDs, WorkModel.DOMAIN),
                new Runs(cloudStore), "cloud", cloudStore);
        site = new Lanes(siteStore, new PgChangeFeed(siteDs, WorkModel.DOMAIN), siteRuns,
                "site", siteStore);
    }

    @AfterAll
    void down() {
        Caller.clear();
    }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return ds;
    }

    /** Work done at the source, by somebody, under a run — and audited there. */
    private Run workDoneAtTheSource(String key, String actor) {
        Run work = siteRuns.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/" + key,
                List.of(WorkModel.DOMAIN));
        Caller.set(actor);
        Caller.setRun(work.key());
        PutResult subject = siteStore.put(PutRequest.create("Gadget",
                ("{\"serial\":\"" + key + "\",\"vendor\":\"acme\",\"name\":\"" + key
                        + "\",\"weightGrams\":\"10\"}").getBytes(StandardCharsets.UTF_8)));
        siteRuns.item(work, "Gadget/" + subject.id(), Failure.RECORD, "needs a second read");
        Caller.clear();
        return work;
    }

    private static List<StoredObject> trailOf(PolicyObjectStore store, String run) {
        return store.select(Criteria.of("AuditEntry").eq("run", EnvelopeValue.of(run)));
    }

    private static String field(StoredObject entry, String name) {
        String json = new String(entry.payload(), StandardCharsets.UTF_8);
        int at = json.indexOf("\"" + name + "\":\"");
        if (at < 0) {
            return null;
        }
        int from = at + name.length() + 4;
        return json.substring(from, json.indexOf('"', from));
    }

    @Test
    @DisplayName("a replica's entries arrive with the actor, the time and the replica that "
            + "recorded them, and the arrival writes no second trail")
    @Proving(DboPromises.PROC_AUDIT_REPLICATES_AS_RECORDED)
    void theTrailArrivesAsItWasRecorded() {
        Run work = workDoneAtTheSource("kask", "dr-kask");
        List<StoredObject> recorded = trailOf(siteStore, work.key());
        assertFalse(recorded.isEmpty(), "the source recorded what it did");
        Instant recordedAt = recorded.get(0).lastUpdated();

        Lanes.Batch batch = site.outbound("cloud", 500, TRAVELS);
        Lanes.Applied applied = cloud.apply("site", batch);
        site.sent("cloud", batch);

        assertTrue(applied.refused().isEmpty(), "nothing was refused: " + applied.refused());
        List<StoredObject> arrived = trailOf(cloudStore, work.key());
        assertEquals(recorded.size(), arrived.size(),
                "every entry the source recorded for that work is here");
        StoredObject entry = arrived.get(0);
        assertEquals("dr-kask", field(entry, "actor"),
                "the actor is the one who did it, not the lane that carried it");
        assertEquals("site", field(entry, "appliance"),
                "and the replica it happened on, which the actor alone cannot say");
        assertEquals(recordedAt, entry.lastUpdated(),
                "the time is when the source recorded it, never when the receiver heard — "
                        + "that is the whole of what such an entry is evidence about");

        // The second trail this must not grow: an arrival is not an
        // interaction, and a store that audited its own replication would
        // write one entry per entry, forever.
        assertEquals(0, cloudStore.count(Criteria.of("AuditEntry")
                        .eq("targetType", EnvelopeValue.of("AuditEntry"))),
                "the arrival of a trail is not itself an audited event");
    }

    @Test
    @DisplayName("a lane delivers at least once, and the trail lands exactly once")
    @Proving(DboPromises.PROC_AUDIT_REPLICATES_AS_RECORDED)
    void aReDeliveredTrailLandsOnce() {
        Run work = workDoneAtTheSource("tamm", "dr-tamm");
        Lanes.Batch batch = site.outbound("cloud", 500, TRAVELS);

        cloud.apply("site", batch);
        long afterFirst = cloudStore.count(Criteria.of("AuditEntry"));
        Lanes.Applied again = cloud.apply("site", batch);
        site.sent("cloud", batch);

        assertEquals(afterFirst, cloudStore.count(Criteria.of("AuditEntry")),
                "the same batch applied twice leaves one copy of each entry");
        assertTrue(again.skipped() > 0, "and says it had them already: " + again);
        assertFalse(trailOf(cloudStore, work.key()).isEmpty(), "the work's trail is here");
    }

    @Test
    @DisplayName("the refusal still stands for everybody else, and says where the one way "
            + "through is")
    @Proving(DboPromises.PROC_AUDIT_REPLICATES_AS_RECORDED)
    void theRefusalStandsAndNamesTheAdmission() {
        PolicyViolationException refused = assertThrows(PolicyViolationException.class,
                () -> cloudStore.put(PutRequest.create("AuditEntry",
                        AuditModel.entry("forger", "create", "Gadget", "g1", "ok", null))));

        assertTrue(refused.getMessage().contains("AuditReplay"),
                "a reader who meets the refusal is asking exactly where the admission is: "
                        + refused.getMessage());
        // The port is not a way to write an entry of one's own: every argument
        // it takes is the source's, and it claims the source's identity — so a
        // second replay of the same entry finds the first rather than
        // appending a different story under it.
        Run work = workDoneAtTheSource("saar", "dr-saar");
        StoredObject recorded = trailOf(siteStore, work.key()).get(0);
        assertTrue(cloudStore.replayAuditEntry("site", recorded.id(), recorded.versionId(),
                recorded.payload(), recorded.lastUpdated()), "the first replay writes it");
        assertFalse(cloudStore.replayAuditEntry("site", recorded.id(), recorded.versionId(),
                        AuditModel.entry("somebody-else", "create", "Gadget", "g1", "ok", null),
                        Instant.now()),
                "and a second one under the same source identity does not overwrite it");
        assertEquals("dr-saar", field(trailOf(cloudStore, work.key()).get(0), "actor"),
                "the entry still says who actually did it");
    }
}
