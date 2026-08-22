package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirTerminology;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.terminology.TerminologyStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant can resolve the codes its store holds, whichever FHIR version it was
 * given (REQ-DBO-TERM-EVERY-TENANT-ANSWERS — "no tenant is a second-class
 * reader").
 *
 * <p>The matrix runs through {@link FhirTerminology} rather than through a
 * face's own class, so the assertion is not "the element face works" but "the
 * three faces answer the same" — which is the promise a tenant is actually
 * given when it is assigned a version.
 *
 * <p>It would have failed on r6 before the element face grew a terminology
 * surface: every operation refused, and dbo's own vocabularies were written
 * whole rather than ingested, so a tenant on the newest version could read its
 * definitions and ask nothing about them (#101).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminologyOnEveryVersionIT {

    private static final String SYS = "https://terms.dbo.test/tree";

    static PostgreSQLContainer<?> postgres;
    static final Map<String, FhirTerminology> FACES = new LinkedHashMap<>();

    static List<String> versions() {
        return List.of("r4", "r5", "r6");
    }

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        for (String code : versions()) {
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(SharedPostgres.urlFor("TerminologyOnEveryVersionIT_" + code));
            pg.setUser(postgres.getUsername());
            pg.setPassword(postgres.getPassword());

            FhirVersion.ForTypes declared = FhirVersions.installed().require(code)
                    .forTypes(List.of(FhirTypeConfig.canonical("CodeSystem"),
                            FhirTypeConfig.canonical("ValueSet")));
            PgObjectStore engine = new PgObjectStore(pg, declared.registrations());
            new TerminologyStore(pg); // creates the concept tables this tenant answers from
            FACES.put(code, declared.terminology(engine, pg));
        }
    }

    /**
     * The stored form is a shell and the resource form is reassembled from
     * concept rows — the truth-form inversion, asserted for every face rather
     * than for the one it was first written in.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    void theShellIsConceptFreeAndReassemblyRestoresTheTree(String code) {
        FhirTerminology terminology = FACES.get(code);
        FhirTerminology.IngestResult result = terminology.ingestCodeSystem(treeCodeSystem());
        assertEquals(6, result.conceptCount(), "every concept in the tree, flattened");

        // What travels is what a client would have posted: the concepts back in
        // the document, each exactly once.
        String reassembled = new String(terminology.forTransport("CodeSystem",
                shellOf(terminology)), StandardCharsets.UTF_8);
        assertTrue(reassembled.contains("\"content\":\"complete\""),
                code + ": the original content mode survives the shell round-trip: "
                        + reassembled);
        assertTrue(reassembled.contains("\"code\":\"cbc\"") && reassembled.contains("\"code\":\"panel\""),
                code + ": the tree comes back: " + reassembled);
        assertEquals(1, occurrences(reassembled, "\"code\":\"cbc\""),
                code + ": each code exactly once — twice is a sync round whose COPY "
                        + "collides with itself and never acks (#97): " + reassembled);
        assertTrue(reassembled.contains("Verepaneel"),
                code + ": designations survive: " + reassembled);
        assertTrue(reassembled.contains("whole-blood"),
                code + ": properties survive: " + reassembled);
        // the child sits under its parent, not beside it
        assertTrue(reassembled.indexOf("\"code\":\"blood\"") < reassembled.indexOf("\"code\":\"cbc\""),
                code + ": the hierarchy is a hierarchy: " + reassembled);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    void lookupAndValidateAnswerFromConceptRows(String code) {
        FhirTerminology terminology = FACES.get(code);
        terminology.ingestCodeSystem(treeCodeSystem());

        String lookup = terminology.lookup(SYS, "blood").orElseThrow();
        assertTrue(lookup.contains("Blood panel"), code + ": " + lookup);
        assertTrue(lookup.contains("Verepaneel"), code + ": designations are looked up too: " + lookup);
        assertTrue(lookup.contains("whole-blood"), code + ": properties too: " + lookup);

        assertTrue(terminology.validateCode(SYS, "cbc").contains("\"valueBoolean\":true"),
                code + ": a held code is in the system");
        assertTrue(terminology.validateCode(SYS, "nope").contains("\"valueBoolean\":false"),
                code + ": and an unheld one is not");
        assertTrue(terminology.lookup(SYS, "nope").isEmpty(),
                code + ": $lookup has nothing to return for a code that is not there");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    void expandServesEveryComposeFlavour(String code) {
        FhirTerminology terminology = FACES.get(code);
        terminology.ingestCodeSystem(treeCodeSystem());

        terminology.ingestValueSet("""
                {"resourceType":"ValueSet","status":"active","url":"https://vs.dbo.test/enum",
                 "compose":{"include":[{"system":"%s","concept":[
                   {"code":"cbc"},{"code":"urine"}]}]}}""".formatted(SYS));
        String enumerated = terminology.expand("https://vs.dbo.test/enum", null, 0, 100)
                .orElseThrow();
        assertTrue(enumerated.contains("\"code\":\"cbc\"") && enumerated.contains("\"code\":\"urine\""),
                code + ": an enumerated compose expands to what it names: " + enumerated);
        assertFalse(enumerated.contains("\"code\":\"lipids\""),
                code + ": and to nothing else: " + enumerated);

        terminology.ingestValueSet("""
                {"resourceType":"ValueSet","status":"active","url":"https://vs.dbo.test/blood",
                 "compose":{"include":[{"system":"%s","filter":[
                   {"property":"concept","op":"is-a","value":"blood"}]}]}}""".formatted(SYS));
        String isA = terminology.expand("https://vs.dbo.test/blood", null, 0, 100).orElseThrow();
        assertTrue(isA.contains("\"code\":\"cbc\"") && isA.contains("\"code\":\"lipids\""),
                code + ": an is-a filter reaches the descendants: " + isA);
        assertFalse(isA.contains("\"code\":\"urine\""),
                code + ": and not the rest of the system: " + isA);

        assertTrue(terminology.expand("https://vs.dbo.test/never-registered", null, 0, 100).isEmpty(),
                code + ": an unregistered ValueSet is nothing to expand, not an empty expansion");
    }

    /** The shell as the engine stored it — what {@code forTransport} is handed. */
    private static byte[] shellOf(FhirTerminology terminology) {
        return ("{\"resourceType\":\"CodeSystem\",\"status\":\"active\","
                + "\"content\":\"not-present\",\"url\":\"" + SYS + "\",\"version\":\"1\","
                + "\"name\":\"Tree\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
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
                   {"code":"single","display":"Single tests"}]}""".formatted(SYS);
    }
}
