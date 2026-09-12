package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the compiler actually accepts, counted over every invariant the carried
 * versions publish.
 *
 * <p>The inventory on the parent issue classified expressions by the functions
 * they use and put the translatable share near ninety per cent. That was a
 * measurement of the shape of the problem, and it said so: an expression can
 * use only translatable functions and still not translate, through precedence,
 * coercion, or a construct an allowlist cannot see. This is the number that
 * replaces it — not what could be compiled in principle, but what is.
 */
class WhatTheInvariantCompilerAcceptsTest {

    @Test
    @DisplayName("what compiles and what does not, said out loud and held to")
    @Proving({DboPromises.VAL_AN_INVARIANT_IS_COMPILED_WHEN_IT_ARRIVES,
            DboPromises.VAL_AN_INVARIANT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME})
    void whatCompilesIsCountedAndWhatDoesNotIsNamed() {
        for (String face : List.of("r4", "r5")) {
            Map<String, String> byExpression = invariantsOf(face);
            Map<String, Integer> refusedBecause = new TreeMap<>();
            int compiled = 0;
            for (String expression : byExpression.keySet()) {
                ExpressionPaths.Predicate result = ExpressionPaths.predicate(expression);
                if (result.enforceable()) {
                    compiled++;
                    assertTrue(result.path().length() > 1,
                            expression + " compiled to nothing: " + result.path());
                } else {
                    refusedBecause.merge(shortened(result.why()), 1, Integer::sum);
                }
            }
            int total = byExpression.size();
            System.out.printf("METRICS invariants %s distinct=%d compiled=%d (%d%%)%n",
                    face, total, compiled, compiled * 100 / total);
            refusedBecause.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(12)
                    .forEach(why -> System.out.printf("  %4d  %s%n", why.getValue(), why.getKey()));

            assertTrue(total > 180, face + " publishes only " + total + " distinct invariants");
            // A floor rather than a target. It exists so that a change which
            // quietly compiles less has to say so here, and the number in the
            // commit message is the one that moves.
            assertTrue(compiled * 100 / total >= 20,
                    face + " compiles " + compiled + " of " + total
                            + ", which is less than this has managed before");
        }
    }

    @Test
    @DisplayName("the shapes it does handle, one by one")
    void theShapesItHandles() {
        assertEquals("!exists($.\"contained\"[*].\"contained\"[*])",
                ExpressionPaths.predicate("contained.contained.empty()").path());
        assertEquals("(exists($.\"name\"[*]) || exists($.\"identifier\"[*]))",
                ExpressionPaths.predicate("name.exists() or identifier.exists()").path());
        assertEquals("$.\"value\"[*] like_regex \"^[A-Z]{3}$\"",
                ExpressionPaths.predicate("value.matches('^[A-Z]{3}$')").path());
        assertEquals("($.\"status\" == \"final\")",
                ExpressionPaths.predicate("status = 'final'").path());
    }

    @Test
    @DisplayName("what it cannot express, it says which part stopped it")
    @Proving(DboPromises.VAL_AN_INVARIANT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME)
    void whatItCannotExpressItNames() {
        ExpressionPaths.Predicate distinct = ExpressionPaths.predicate("linkId.isDistinct()");
        assertTrue(distinct.why() != null && distinct.why().contains("isDistinct"),
                "the refusal does not name the function: " + distinct.why());

        ExpressionPaths.Predicate variable = ExpressionPaths.predicate("%resource.id.exists()");
        assertTrue(variable.why() != null, "a variable compiled to something");
    }

    /** Every distinct invariant expression a version publishes, by expression. */
    private static Map<String, String> invariantsOf(String face) {
        Map<String, String> byExpression = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (FaceRootPackages.Definition definition
                : FaceRootPackages.definitionsFor(face, Set.of("StructureDefinition"))) {
            Object structure = Json.parse(
                    new String(definition.document(), StandardCharsets.UTF_8));
            Object snapshot = structure instanceof Map<?, ?> map ? map.get("snapshot") : null;
            if (snapshot == null) {
                continue;
            }
            for (Object element : Json.array(snapshot, "element")) {
                for (Object constraint : Json.array(element, "constraint")) {
                    Object expression = constraint instanceof Map<?, ?> map
                            ? map.get("expression") : null;
                    if (expression != null && seen.add(String.valueOf(expression))) {
                        byExpression.put(String.valueOf(expression),
                                String.valueOf(((Map<?, ?>) constraint).get("key")));
                    }
                }
            }
        }
        return byExpression;
    }

    /** The reason, without the particulars, so refusals group. */
    private static String shortened(String why) {
        int names = why.indexOf(": ");
        return names < 0 ? why : why.substring(0, names);
    }
}
