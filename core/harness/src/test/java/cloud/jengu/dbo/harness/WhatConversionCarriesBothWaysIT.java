package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.fhir.r4.R5ToR4Converter;
import cloud.jengu.dbo.fhir.r5.R4ToR5Converter;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives a version and back, counted over what a version publishes.
 *
 * <p>A zone publishes in one version and its tenants are on whichever face
 * each of them chose, so a zone written in R5 has to reach R4 tenants as R4
 * definitions. That needs the hop this store never had — downward — and a hop
 * downward loses things by nature: a structure constraining an element R5 has
 * and R4 does not cannot be said in R4, and the convertor may drop it without
 * a word.
 *
 * <p><b>So the number is the point, not the pass.</b> This carries every
 * definition a version publishes forward and back and counts what comes home
 * unchanged, per type, against a recorded baseline. It may fall and may not
 * rise — a change that quietly carries less has to say so here — and what the
 * number is NOT is a claim that the rest is fine. A definition that survives
 * the round trip byte for byte certainly converted; one that did not may have
 * lost a constraint, and which of those it is gets decided where the converted
 * definition is expanded on the target face, not here.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatConversionCarriesBothWaysIT {

    /** Per type, so the common ones do not decide the number for the rest. */
    private static final int PER_TYPE = 80;

    private static final Path BASELINE =
            Path.of("..", "..", "config", "conversion-baseline.txt");

    @Test
    @Timeout(900)
    @DisplayName("a definition carried to the other version and back is counted, and no "
            + "fewer come home than were recorded")
    @Proving(DboPromises.VER_CONVERSION_RUNS_BOTH_WAYS)
    void whatComesHomeIsCountedAndHeldTo() throws Exception {
        R4ToR5Converter up = new R4ToR5Converter();
        R5ToR4Converter down = new R5ToR4Converter();

        assertEquals("4.0", up.fromVersion());
        assertEquals(up.toVersion(), down.fromVersion(),
                "the two hops do not meet: what one produces the other does not take");
        assertEquals(up.fromVersion(), down.toVersion(),
                "the way back does not arrive where the way out started");

        Map<String, int[]> perType = new TreeMap<>();
        for (Map.Entry<String, List<FaceRootPackages.Definition>> ofType : corpus().entrySet()) {
            int[] tally = perType.computeIfAbsent(ofType.getKey(), ignored -> new int[3]);
            for (FaceRootPackages.Definition definition : ofType.getValue()) {
                tally[0]++;
                byte[] there;
                try {
                    there = up.convert(ofType.getKey(), definition.document());
                } catch (RuntimeException notEvenOut) {
                    tally[2]++;
                    continue;
                }
                try {
                    byte[] back = down.convert(ofType.getKey(), there);
                    if (sameDocument(definition.document(), back)) {
                        tally[1]++;
                    }
                } catch (RuntimeException notBack) {
                    tally[2]++;
                }
            }
        }

        int carried = perType.values().stream().mapToInt(t -> t[0]).sum();
        assertTrue(carried > 200, "only " + carried + " definitions were carried");

        String observed = asLines(perType);
        Path baseline = BASELINE.toAbsolutePath().normalize();
        if (!Files.exists(baseline) || Boolean.getBoolean("dbo.conversion.record")) {
            Files.writeString(baseline, PREAMBLE + observed);
            System.out.println("conversion baseline recorded: " + baseline);
            return;
        }
        assertNoWorseThan(Files.readString(baseline), observed);
    }

    /**
     * Per type against its own number, so a type that got worse is named
     * rather than hidden inside a total something else improved.
     */
    private static void assertNoWorseThan(String recorded, String observed) {
        Map<String, int[]> was = parse(recorded);
        Map<String, int[]> now = parse(observed);
        List<String> worse = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : now.entrySet()) {
            int[] before = was.get(entry.getKey());
            if (before == null) {
                continue;
            }
            int[] after = entry.getValue();
            if (after[1] < before[1] || after[2] > before[2]) {
                worse.add(entry.getKey() + ": was home=" + before[1] + " refused=" + before[2]
                        + ", now home=" + after[1] + " refused=" + after[2]);
            }
        }
        assertEquals(List.of(), worse,
                "less of the version survives the round trip than did. Re-record with "
                        + "-Ddbo.conversion.record=true only when the change is meant.\n"
                        + observed);
    }

    // -------------------------------------------------------------- corpus

    private static Map<String, List<FaceRootPackages.Definition>> corpus() {
        Map<String, List<FaceRootPackages.Definition>> byType = new java.util.LinkedHashMap<>();
        for (FaceRootPackages.Definition definition : FaceRootPackages.definitionsFor("r4",
                Set.of("StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"))) {
            List<FaceRootPackages.Definition> held =
                    byType.computeIfAbsent(definition.typeName(), ignored -> new ArrayList<>());
            if (held.size() < PER_TYPE) {
                held.add(definition);
            }
        }
        return byType;
    }

    /**
     * The same document, by what it says rather than by its bytes.
     *
     * <p>A round trip re-serialises, so key order and whitespace move without
     * anything having been lost. What is compared is the parsed form, which is
     * what a reader of the definition would see.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static boolean sameDocument(byte[] first, byte[] second) {
        try {
            return JSON.readTree(first).equals(JSON.readTree(second));
        } catch (java.io.IOException notJson) {
            return false;
        }
    }

    // ------------------------------------------------------------ the file

    private static final String PREAMBLE = """
            # What a definition survives being carried to the other version and back.
            # One line per resource type:
            #
            #   <type> carried=<n> home=<n> refused=<n>
            #
            # GENERATED. Re-record with:
            #     ./gradlew :core:harness:test --tests '*WhatConversionCarriesBothWays*' \\
            #         -Ddbo.conversion.record=true
            #
            # `home` may not fall and `refused` may not rise. A zone written in one
            # version serves tenants on the other, so this is the hop those tenants'
            # definitions actually take.
            #
            # What `home` is not: a bill of health for the rest. A definition that
            # comes home unchanged certainly converted. One that did not may have lost
            # a constraint the older version cannot say — and whether what came out is
            # still the definition it was meant to be is decided where it is expanded
            # on the target face, which is the only place the question is checkable.
            #
            # Which the search parameters show plainly, so read the number rather than
            # recoiling from it: almost none of them come home, and the whole of the
            # difference is `xpath`. R5 removed the field, so the hop out drops it and
            # the hop back cannot invent it. This store compiles `expression` and has
            # never read an xpath, so nothing it does is any different — the count is
            # low because the comparison is strict, which is what makes it a ratchet.
            """;

    private static String asLines(Map<String, int[]> perType) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, int[]> entry : perType.entrySet()) {
            int[] tally = entry.getValue();
            out.append(entry.getKey()).append(" carried=").append(tally[0])
                    .append(" home=").append(tally[1])
                    .append(" refused=").append(tally[2]).append('\n');
        }
        return out.toString();
    }

    private static Map<String, int[]> parse(String lines) {
        Map<String, int[]> out = new TreeMap<>();
        for (String line : lines.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split(" ");
            out.put(parts[0], new int[] {
                    number(parts[1]), number(parts[2]), number(parts[3])});
        }
        return out;
    }

    private static int number(String part) {
        return Integer.parseInt(part.substring(part.indexOf('=') + 1));
    }
}
