package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the compiler makes of every search expression the carried versions
 * publish, counted per parameter and base rather than classified.
 *
 * <p>The inventory on the parent issue put nearly every search expression
 * within reach at the function level. This is the number that replaces it:
 * what actually compiles into a selection the database can run, and what
 * stopped the rest, named.
 */
class WhatTheSearchCompilerAcceptsTest {

    @Test
    @DisplayName("what selects and what does not, said out loud and held to")
    @Proving(DboPromises.VER_AN_EXPRESSION_THAT_YIELDS_A_VALUE_IS_COMPILED)
    void whatSelectsIsCountedAndWhatDoesNotIsNamed() {
        for (String face : List.of("r4", "r5")) {
            Map<String, Integer> refusedBecause = new TreeMap<>();
            Set<String> seen = new LinkedHashSet<>();
            int compiled = 0;
            int asQuestions = 0;
            int total = 0;
            for (FaceRootPackages.Definition definition
                    : FaceRootPackages.definitionsFor(face, Set.of("SearchParameter"))) {
                Object parameter = Json.parse(
                        new String(definition.document(), StandardCharsets.UTF_8));
                Object expression = ((Map<?, ?>) parameter).get("expression");
                if (expression == null) {
                    continue;
                }
                for (String base : Json.strings(parameter, "base")) {
                    if (!seen.add(base + " " + expression)) {
                        continue;
                    }
                    total++;
                    ExpressionPaths.Selection result =
                            ExpressionPaths.selection(String.valueOf(expression), base);
                    if (result.enforceable()) {
                        compiled++;
                        if (result.predicate() != null) {
                            asQuestions++;
                        }
                        for (String path : result.paths()) {
                            assertTrue(path.startsWith("$"),
                                    expression + " on " + base + " selects from " + path);
                        }
                    } else {
                        refusedBecause.merge(shortened(result.why()), 1, Integer::sum);
                    }
                }
            }
            System.out.printf("METRICS search %s parameters=%d compiled=%d (%d%%) asQuestions=%d%n",
                    face, total, compiled, compiled * 100 / total, asQuestions);
            refusedBecause.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(12)
                    .forEach(why -> System.out.printf("  %4d  %s%n", why.getValue(), why.getKey()));

            assertTrue(total > 1000, face + " publishes only " + total + " parameter bases");
            // A floor rather than a target, so a change that quietly selects
            // less has to say so here.
            assertTrue(compiled * 100 / total >= 50,
                    face + " selects for " + compiled + " of " + total
                            + ", which is less than this has managed before");
        }
    }

    @Test
    @DisplayName("the shapes a search expression takes, one by one")
    @Proving(DboPromises.VER_AN_EXPRESSION_THAT_YIELDS_A_VALUE_IS_COMPILED)
    void theShapesItHandles() {
        assertEquals(List.of("$.\"name\"[*]"),
                ExpressionPaths.selection("Patient.name", "Patient").paths());
        assertEquals(List.of("$.\"valueQuantity\"[*]"),
                ExpressionPaths.selection("Observation.value as Quantity", "Observation").paths());
        assertEquals(List.of("$.\"valueQuantity\"[*]", "$.\"valueSampledData\"[*]"),
                ExpressionPaths.selection(
                        "(Observation.value as Quantity) | (Observation.value as SampledData)",
                        "Observation").paths());
        assertEquals(List.of("$.\"subject\"[*] ? (($.\"reference\" starts with \"Patient/\"))"
                        .replace("($.\"reference\"", "(@.\"reference\"")),
                ExpressionPaths.selection("Condition.subject.where(resolve() is Patient)",
                        "Condition").paths());
        assertEquals(List.of("$.\"telecom\"[*] ? ((@.\"system\" == \"email\"))"),
                ExpressionPaths.selection("Patient.telecom.where(system='email')", "Patient")
                        .paths());
        // a union across types selects only this type's branch
        assertEquals(List.of("$.\"name\"[*]"),
                ExpressionPaths.selection("Patient.name | Person.name", "Patient").paths());
        // a question yields its answer as the one value
        ExpressionPaths.Selection deceased = ExpressionPaths.selection(
                "Patient.deceased.exists() and Patient.deceased != false", "Patient");
        assertNotNull(deceased.predicate(), "a token over a truth did not compile: "
                + deceased.why());
    }

    @Test
    @DisplayName("what it cannot select, it says which part stopped it")
    @Proving(DboPromises.VER_AN_EXPRESSION_THAT_YIELDS_A_VALUE_IS_COMPILED)
    void whatItCannotSelectItNames() {
        ExpressionPaths.Selection followed =
                ExpressionPaths.selection("Observation.subject.resolve().name", "Observation");
        assertTrue(followed.why() != null && followed.why().contains("resolve"),
                "following a reference compiled to something: " + followed);
        ExpressionPaths.Selection elsewhere =
                ExpressionPaths.selection("Person.name", "Patient");
        assertTrue(elsewhere.why() != null && elsewhere.why().contains("about a Patient"),
                "another type's expression compiled for this one: " + elsewhere);
    }

    /** The reason, without the particulars, so refusals group. */
    private static String shortened(String why) {
        int names = why.indexOf(": ");
        return names < 0 ? why : why.substring(0, names);
    }
}
