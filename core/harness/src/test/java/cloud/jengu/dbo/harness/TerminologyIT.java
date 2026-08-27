package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r4.R4Terminology;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.terminology.TerminologyStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proof matrix: the terminology truth-form inversion. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminologyIT {

    static final String BIG_SYS = "https://terms.dbo.test/big";
    static final String TREE_SYS = "https://terms.dbo.test/tree";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static PgObjectStore store;
    static R4Terminology terminology;
    static TerminologyStore nativeStore;
    static R4Personality personality;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("TerminologyIT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(jdbcUrl);
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());

        personality = new R4Personality(List.of(
                FhirTypeConfig.canonical("CodeSystem"),
                FhirTypeConfig.canonical("ValueSet")));
        store = new PgObjectStore(pg, personality.registrations());
        nativeStore = new TerminologyStore(pg);
        terminology = new R4Terminology(store, personality, nativeStore);
    }

    @AfterAll
    void down() {
    }

    private static String bigCodeSystem(int concepts, String version) {
        StringBuilder sb = new StringBuilder("""
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"%s","version":"%s","name":"Big","concept":[""".formatted(BIG_SYS, version));
        for (int i = 0; i < concepts; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"code\":\"C%06d\",\"display\":\"Concept %d\"}".formatted(i, i));
        }
        return sb.append("]}").toString();
    }

    private static String treeCodeSystem() {
        return """
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"%s","version":"1","name":"Tree","concept":[
                   {"code":"panel","display":"Panels","concept":[
                     {"code":"blood","display":"Blood panel",
                      "designation":[{"language":"et","value":"Verepaneel"}],
                      "property":[{"code":"specimen","valueString":"whole-blood"}],
                      "concept":[
                        {"code":"cbc","display":"Complete blood count"},
                        {"code":"lipids","display":"Lipid panel"}]},
                     {"code":"urine","display":"Urine panel"}]},
                   {"code":"single","display":"Single tests"}]}""".formatted(TREE_SYS);
    }

    /** REQ-DBO-TERM-BULK-LOAD: 40k concepts, one COPY, no parameter ceiling; re-ingest replaces. */
    @Test
    @Timeout(180)
    void fortyThousandConceptsIngestInOneCopy() {
        long t0 = System.nanoTime();
        R4Terminology.IngestResult first = terminology.ingestCodeSystem(bigCodeSystem(40_000, "1.0"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("METRICS terminology-ingest 40k concepts in %dms%n", ms);

        assertEquals(40_000, first.conceptCount());
        assertEquals(40_000, nativeStore.conceptCount(BIG_SYS));
        assertTrue(nativeStore.validateCode(BIG_SYS, "C012345"));
        assertEquals("Concept 39999",
                nativeStore.lookup(BIG_SYS, "C039999").orElseThrow().display());

        // re-ingest with fewer concepts REPLACES (no stale rows)
        R4Terminology.IngestResult second = terminology.ingestCodeSystem(bigCodeSystem(1_000, "2.0"));
        assertEquals(first.id(), second.id(), "same canonical → same engine object");
        assertTrue(second.versionId() > first.versionId());
        assertEquals(1_000, nativeStore.conceptCount(BIG_SYS));
        assertFalse(nativeStore.validateCode(BIG_SYS, "C012345"));
    }

    /** The truth-form inversion: shell payload carries no concepts; reassembly restores the tree. */
    @Test
    @Proving(DboPromises.CORE_DECLARED_TRUTH_FORM)
    void shellIsConceptFreeAndReassemblyRestoresTheTree() {
        R4Terminology.IngestResult result = terminology.ingestCodeSystem(treeCodeSystem());

        String shellPayload = new String(
                store.get("CodeSystem", result.id()).orElseThrow().payload());
        assertFalse(shellPayload.contains("\"cbc\""),
                "engine payload must be the concept-free shell");
        assertTrue(shellPayload.contains("\"count\":6"));

        String reassembled = terminology.codeSystemResource(TREE_SYS).orElseThrow();
        assertTrue(reassembled.contains("\"cbc\""));
        assertTrue(reassembled.contains("Verepaneel"));
        // hierarchy preserved: cbc nested under blood, blood under panel
        int panel = reassembled.indexOf("\"panel\"");
        int blood = reassembled.indexOf("\"blood\"");
        int cbc = reassembled.indexOf("\"cbc\"");
        assertTrue(panel < blood && blood < cbc, "tree order lost in reassembly");
    }

    /**
     * A CodeSystem stored WHOLE still travels with each code once (#97).
     *
     * <p>The stored form is meant to be a shell, its concepts living natively —
     * but a CodeSystem written some other way keeps them in the document, and
     * transport used to append the native concepts to the ones already there.
     * Each code went out twice, the receiving COPY collided with itself on
     * (system, code), and the sync round never acked: the same item retried
     * from the same cursor forever, thousands of stack traces per boot, with
     * everything queued behind it on that stream stuck too.
     *
     * <p>dbo published its own run vocabulary this way in e217f4c (#91), which
     * is how a latent shape became a live deadlock — but the fault is in
     * transport, so this holds transport to it rather than only fixing the
     * one document that exposed it.
     */
    @Test
    void aCodeSystemStoredWholeTravelsWithEachCodeOnce() {
        String url = "urn:dbo:test:stored-whole";
        String whole = """
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"%s","version":"1","name":"StoredWhole","concept":[
                   {"code":"AUTOMATION"},{"code":"RETRY"},
                   {"code":"PERSON"},{"code":"NOBODY"}]}""".formatted(url);

        // The production sequence, and the order is the whole point. A sync
        // hop ingests the system, so the concepts reach the native form and
        // the stored document becomes a shell — and then a later bring-up
        // republishes the vocabulary the ORDINARY way, overwriting that shell
        // with a document that carries its concepts again. Now both halves
        // hold the same four codes.
        terminology.ingestCodeSystem(whole);
        new R4Store(store, personality, "").putCanonical(whole);

        String onTheWire = new String(terminology.forTransport("CodeSystem",
                whole.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.nio.charset.StandardCharsets.UTF_8);

        for (String code : List.of("AUTOMATION", "RETRY", "PERSON", "NOBODY")) {
            assertEquals(1, onTheWire.split("\"" + code + "\"", -1).length - 1,
                    code + " must appear once on the wire, or the receiver's COPY collides "
                            + "with itself: " + onTheWire);
        }
    }

    /** $expand flavors: enumerated, is-a descendants, exclude, whole-system paging, prefix filter. */
    @Test
    void expandServesAllComposeFlavorsFromConceptRows() {
        terminology.ingestCodeSystem(treeCodeSystem());

        // enumerated include
        terminology.ingestValueSet("""
                {"resourceType":"ValueSet","status":"active","url":"https://vs.dbo.test/enum",
                 "compose":{"include":[{"system":"%s","concept":[
                   {"code":"cbc"},{"code":"urine"}]}]}}""".formatted(TREE_SYS));
        String enumerated = terminology.expand("https://vs.dbo.test/enum", null, 0, 100).orElseThrow();
        assertEquals(2, countContains(enumerated));

        // is-a descendants (self included) minus exclude
        terminology.ingestValueSet("""
                {"resourceType":"ValueSet","status":"active","url":"https://vs.dbo.test/blood",
                 "compose":{"include":[{"system":"%s","filter":[
                     {"property":"concept","op":"is-a","value":"blood"}]}],
                   "exclude":[{"system":"%s","concept":[{"code":"lipids"}]}]}}"""
                .formatted(TREE_SYS, TREE_SYS));
        String isA = terminology.expand("https://vs.dbo.test/blood", null, 0, 100).orElseThrow();
        assertEquals(2, countContains(isA)); // blood + cbc (lipids excluded)
        assertTrue(isA.contains("\"cbc\""));
        assertFalse(isA.contains("\"lipids\""));

        // whole system, paged
        terminology.ingestValueSet("""
                {"resourceType":"ValueSet","status":"active","url":"https://vs.dbo.test/all",
                 "compose":{"include":[{"system":"%s"}]}}""".formatted(TREE_SYS));
        String page1 = terminology.expand("https://vs.dbo.test/all", null, 0, 4).orElseThrow();
        String page2 = terminology.expand("https://vs.dbo.test/all", null, 4, 4).orElseThrow();
        assertEquals(4, countContains(page1));
        assertEquals(2, countContains(page2));
        assertTrue(page1.contains("\"total\":6"));

        // prefix filter (suggestion-engine shape)
        String filtered = terminology.expand("https://vs.dbo.test/all", "bl", 0, 100).orElseThrow();
        assertEquals(1, countContains(filtered));
        assertTrue(filtered.contains("Blood panel"));
    }

    /** $lookup carries display, designations and properties; $validate-code answers both ways. */
    @Test
    void lookupAndValidateServeFromConceptRows() {
        terminology.ingestCodeSystem(treeCodeSystem());

        String lookup = terminology.lookup(TREE_SYS, "blood").orElseThrow();
        assertTrue(lookup.contains("Blood panel"));
        assertTrue(lookup.contains("Verepaneel"));
        assertTrue(lookup.contains("whole-blood"));

        assertTrue(terminology.validateCode(TREE_SYS, "cbc").contains("\"valueBoolean\":true"));
        assertTrue(terminology.validateCode(TREE_SYS, "nope").contains("\"valueBoolean\":false"));
    }

    private static int countContains(String valueSetJson) {
        int n = 0;
        int i = -1;
        while ((i = valueSetJson.indexOf("\"code\":", i + 1)) >= 0) {
            n++;
        }
        return n;
    }
}
