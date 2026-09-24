package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.Payloads;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index checker and the toolchain, asked the same question.
 *
 * <p>{@link AThirdAnswererChecksCardinalityTest} says the checker faults
 * nothing the specification ships and finds the faults put in front of it.
 * That is a checker measured against documents. This measures it against the
 * answerer that currently DECIDES — because a second implementation is only
 * worth having if it says the same thing, and "it passed my own tests" is how
 * a second implementation quietly becomes a different specification.
 *
 * <p>The database is the third leg and is not here: {@code dbo.cardinality}
 * needs a tenant, and this spike lives where there is no database. So this is
 * two answers, not three, and what it establishes is the one comparison that
 * can be made before the index earns a module.
 *
 * <p><b>Divergences are recorded, not asserted away.</b> The house pattern is
 * {@code TheTwoAnswersAreComparedOverTheVersionIT}, which holds a number of
 * explained divergences rather than a claim of identity. What must not happen
 * is the checker MISSING what the toolchain refuses, so that is what fails.
 */
final class TheThirdAnswererAgreesWithTheToolchainTest {

    private static final Set<String> CARRIED = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "ConceptMap", "OperationDefinition", "CompartmentDefinition"));

    /** How many of the carried corpus to put through both, keeping the run honest and finite. */
    private static final int SAMPLE = 400;

    @Test
    void theTwoAgreeOnWhatIsMissing() throws IOException {
        FlatDefinitionIndex index = AThirdAnswererChecksCardinalityTest.indexOverR5();
        ElementPayloads payloads = ElementVersion.of("r5").payloadsFor(Terms.NONE);

        Map<String, byte[]> bytes = new LinkedHashMap<>();
        bytes.put("Observation|urn:test:no-status-no-code", """
                {"resourceType":"Observation"}""".getBytes(StandardCharsets.UTF_8));
        bytes.put("Patient|urn:test:a-link-without-its-other", """
                {"resourceType":"Patient","link":[{"type":"refer"}]}"""
                .getBytes(StandardCharsets.UTF_8));
        int taken = 0;
        for (Map.Entry<String, byte[]> one : AThirdAnswererChecksCardinalityTest
                .carriedResourcesOfR5().entrySet()) {
            if (taken++ >= SAMPLE) {
                break;
            }
            bytes.put(one.getKey(), one.getValue());
        }

        List<String> missed = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        int agreed = 0;
        int said = 0;
        for (Map.Entry<String, byte[]> one : bytes.entrySet()) {
            String type = one.getKey().substring(0, one.getKey().indexOf('|'));
            Set<String> fromToolchain = minimaOf(payloads, type, one.getValue());
            Set<String> fromIndex = new LinkedHashSet<>();
            for (var finding : AThirdAnswererChecksCardinalityTest.check(
                    index, FlatDefinitionIndex.PREFIX + type, one.getValue())) {
                if (finding.detail().startsWith("minimum")) {
                    fromIndex.add(withoutIndices(finding.path()));
                }
            }
            said += fromToolchain.size();
            for (String path : fromToolchain) {
                if (fromIndex.contains(path)) {
                    agreed++;
                } else {
                    missed.add(one.getKey() + " -> " + path);
                }
            }
            for (String path : fromIndex) {
                if (!fromToolchain.contains(path)) {
                    extra.add(one.getKey() + " -> " + path);
                }
            }
        }

        System.out.printf("%n=== minima: the index checker against the toolchain, %d documents ===%n",
                bytes.size());
        System.out.printf("the toolchain reported   %4d%n", said);
        System.out.printf("  the checker agreed     %4d%n", agreed);
        System.out.printf("  the checker missed     %4d  %s%n", missed.size(), missed);
        System.out.printf("  the checker added      %4d  %s%n", extra.size(), extra);
        System.out.println();

        assertTrue(said > 0, "the toolchain reported nothing, so this compared nothing");
        assertTrue(missed.isEmpty(),
                "the index checker missed what the toolchain refuses: " + missed);
        assertTrue(extra.isEmpty(),
                "the index checker refuses what the toolchain accepts: " + extra);
    }

    /**
     * Where the two do NOT agree, and which of them is right.
     *
     * <p>An element the definition allows once, arriving as an array: the
     * toolchain reports nothing, and the document it parsed has lost the
     * value entirely — {@code {"gender":["female","male"]}} comes back as
     * {@code {"resourceType":"Patient"}}. The write would be accepted and
     * what was stored would not be what was sent.
     *
     * <p>That is the case for a second answerer stated more sharply than any
     * memory figure: it is not only cheaper to hold, it sees a document being
     * silently emptied. Recorded as a test rather than as a paragraph, so
     * that if the toolchain starts reporting it this fails and is revisited
     * instead of quietly remaining true in a document nobody rereads.
     */
    @Test
    void theToolchainDropsWhatItCannotFitAndSaysNothing() throws IOException {
        FlatDefinitionIndex index = AThirdAnswererChecksCardinalityTest.indexOverR5();
        ElementPayloads payloads = ElementVersion.of("r5").payloadsFor(Terms.NONE);

        record Case(String type, String document, String element) {
        }
        List<Case> cases = List.of(
                new Case("Patient",
                        """
                        {"resourceType":"Patient","gender":["female","male"]}""",
                        "Patient.gender"),
                new Case("Patient",
                        """
                        {"resourceType":"Patient","contact":[{"name":[{"family":"a"},\
                        {"family":"b"}]}]}""",
                        "Patient.contact.name"),
                new Case("Patient",
                        """
                        {"resourceType":"Patient","name":[{"family":["a","b"]}]}""",
                        "Patient.name.family"));

        System.out.println();
        System.out.println("=== an element allowed once, sent twice ===");
        for (Case one : cases) {
            byte[] document = one.document().getBytes(StandardCharsets.UTF_8);
            var parsed = payloads.read(one.type(), document);
            List<Payloads.Issue> issues = payloads.check(one.type(), parsed, null);
            String roundTripped = new String(payloads.write(parsed), StandardCharsets.UTF_8);
            Set<String> fromIndex = new LinkedHashSet<>();
            for (var finding : AThirdAnswererChecksCardinalityTest.check(
                    index, FlatDefinitionIndex.PREFIX + one.type(), document)) {
                fromIndex.add(withoutIndices(finding.path()));
            }
            System.out.printf("  %-22s toolchain errors: %d   checker: %s%n",
                    one.element(), issues.stream().filter(Payloads.Issue::refuses).count(),
                    fromIndex.contains(one.element()) ? "reports it" : "SILENT");
            System.out.println("      round-trips as: " + roundTripped);

            assertTrue(fromIndex.contains(one.element()),
                    "the index checker did not report " + one.element());
            assertTrue(issues.stream().noneMatch(i -> i.refuses()
                            && i.message() != null && i.message().contains("aximum")),
                    "the toolchain now reports this maximum — the divergence this "
                            + "records has closed, and the item should say so: " + issues);
            assertTrue(!roundTripped.contains("female") && !roundTripped.contains("\"a\""),
                    "the toolchain no longer drops the value, so this case is stale: "
                            + roundTripped);
        }
        System.out.println();
    }

    /** What the toolchain says is missing, by the path its MESSAGE names. */
    private static Set<String> minimaOf(ElementPayloads payloads, String type, byte[] document) {
        Set<String> out = new LinkedHashSet<>();
        List<Payloads.Issue> issues;
        try {
            issues = payloads.check(type, payloads.read(type, document), null);
        } catch (RuntimeException unreadable) {
            return out;
        }
        for (Payloads.Issue issue : issues) {
            String message = issue.message();
            // The location is the PARENT — "Observation" for a missing
            // Observation.status — and the path is the first thing in the
            // message. Comparing locations would compare notation, and would
            // also collapse two missing children of one parent into one.
            int cut = message == null ? -1 : message.indexOf(": minimum required");
            if (cut > 0) {
                out.add(withoutIndices(message.substring(0, cut)));
            }
        }
        return out;
    }

    /**
     * A path with its repeats' positions removed.
     *
     * <p>The two answerers number repeats differently — one reports
     * {@code Patient.contact[0].name}, the other {@code Patient.contact.name}
     * — and a comparison that treated that as disagreement would be measuring
     * the notation rather than the verdict.
     */
    private static String withoutIndices(String path) {
        return path == null ? "" : path.replaceAll("\\[[0-9]+\\]", "");
    }

}
