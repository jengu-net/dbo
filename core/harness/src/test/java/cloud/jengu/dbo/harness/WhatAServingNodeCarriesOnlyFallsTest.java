package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The definition packages a serving node carries, recorded, and they may only
 * fall.
 *
 * <p>Item 025's last move is a property: a serving node that carries no
 * definition packages cannot populate a worker context, whatever classes it
 * holds — {@code SimpleWorkerContext} with nothing to load is a class, not a
 * graph. It takes a few hundred megabytes of heap out of a face and 65 of jar
 * out of a distribution as a side effect.
 *
 * <p><b>It cannot be asserted yet, and this says so with a number instead of
 * a paragraph.</b> The property holds only when nothing on the serving path
 * BUILDS a context, and something still does: {@code ElementPayloads.read}
 * parses every write into an {@code elementmodel.Element}, which needs a
 * populated context whether or not anything validates. The checker and the
 * envelope that would replace it exist and agree with the database, and
 * nothing on the write path asks them for anything — which
 * {@code config/reach-ledger.txt} says by name.
 *
 * <p>So what is ratcheted is the ceiling. A number in a file falls as the
 * serving path stops needing the packages, and fails the build if it rises.
 * When it reaches zero the ratchet and the property are the same statement,
 * and this test becomes the one item 025 asked for rather than a stand-in for
 * it.
 */
class WhatAServingNodeCarriesOnlyFallsTest {

    private static final Path BASELINE =
            Path.of("..", "..", "config", "carried-packages.txt");

    private static final String PREAMBLE = """
            # The definition packages a serving node still carries, in bytes.
            #
            # GENERATED. Re-record with:
            #     ./gradlew :core:harness:test \\
            #         --tests '*WhatAServingNodeCarriesOnlyFalls*' \\
            #         -Ddbo.packages.record=true
            #
            # It may fall and may not rise. A node carrying no definition
            # packages cannot populate a worker context, whatever classes it
            # holds.
            #
            # The packages are a FRAGMENT of the face, shipped beside a
            # distribution's bundle set and installed only where a node has
            # to turn a version into records. The serving set carries none,
            # which is why this reads zero — and zero here is the property
            # rather than a ceiling approaching it.
            #
            """;

    /**
     * The fragment the packages live in, which is not a serving bundle.
     *
     * <p>Named rather than discovered, because the whole claim is that this
     * one is absent from what a node serves: a scan that decided membership
     * by what happened to be staged would call the claim true whenever a
     * build staged less.
     */
    private static final String THE_FRAGMENT = "dbo.fhir.packages.jar";

    @Test
    @DisplayName("a serving node carries no definition packages at all, and the fragment that "
            + "does carry them is not one of its bundles")
    void theCarriedPackagesOnlyFall() throws IOException {
        // THE SCAN HAS TO FIND THE PACKAGES SOMEWHERE, or every assertion
        // below is true of a run that staged nothing. They are in the
        // fragment, and finding them there is what makes finding none in the
        // serving set mean anything.
        String fragment = System.getProperty(THE_FRAGMENT);
        assertTrue(fragment != null && Files.exists(Path.of(fragment)),
                "the packages fragment was not staged, so this run cannot tell a serving node "
                        + "that carries no packages from a test that scanned nothing");
        assertTrue(packagedIn(Path.of(fragment)) > 0,
                "the fragment that exists to carry the definition packages carries none, so "
                        + "nothing in this build has them and the assertions below are vacuous");

        Map<String, Long> carried = new TreeMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (!name.startsWith("dbo.") || !name.endsWith(".jar") || name.equals(THE_FRAGMENT)) {
                continue;
            }
            Path jar = Path.of(System.getProperty(name));
            if (!Files.exists(jar)) {
                continue;
            }
            long bytes = packagedIn(jar);
            if (bytes > 0) {
                carried.put(name.substring(0, name.length() - ".jar".length()), bytes);
            }
        }

        long total = carried.values().stream().mapToLong(Long::longValue).sum();
        StringBuilder observed = new StringBuilder();
        carried.forEach((module, bytes) -> observed.append(module).append(' ')
                .append(bytes).append('\n'));
        observed.append("total ").append(total).append('\n');

        Path baseline = BASELINE.toAbsolutePath().normalize();
        if (!Files.exists(baseline) || Boolean.getBoolean("dbo.packages.record")) {
            Files.writeString(baseline, PREAMBLE + observed, StandardCharsets.UTF_8);
            System.out.println("carried packages recorded: " + baseline);
            return;
        }
        assertNoMoreThan(Files.readString(baseline), observed.toString());
    }

    /**
     * Per module, so a bundle that started carrying packages is named rather
     * than hidden inside a total another one improved.
     */
    private static void assertNoMoreThan(String recorded, String observed) {
        Map<String, Long> was = parse(recorded);
        Map<String, Long> now = parse(observed);
        List<String> worse = new ArrayList<>();
        for (Map.Entry<String, Long> entry : now.entrySet()) {
            long before = was.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() > before) {
                worse.add(entry.getKey() + ": was " + before + ", now " + entry.getValue());
            }
        }
        assertEquals(List.of(), worse,
                "a serving node carries more definition packages than it did. Re-record with "
                        + "-Ddbo.packages.record=true only when the change is meant.\n" + observed);
    }

    private static Map<String, Long> parse(String lines) {
        Map<String, Long> out = new TreeMap<>();
        for (String line : lines.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split(" ");
            out.put(parts[0], Long.parseLong(parts[1]));
        }
        return out;
    }

    /**
     * The definition packages inside one bundle.
     *
     * <p>Their uncompressed size, which is what a context would have to parse,
     * rather than what the entry costs on disk.
     */
    private static long packagedIn(Path jar) throws IOException {
        long bytes = 0;
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("definitions/")
                        && entry.getName().endsWith(".tgz")) {
                    bytes += entry.getSize();
                }
            }
        }
        return bytes;
    }
}
