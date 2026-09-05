package cloud.jengu.dbo.harness;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Every production class that no other production class names.
 *
 * <p>This store's characteristic bug is a toolset that is built, proven and
 * unreachable: a harness <i>is</i> the container and constructs whatever it
 * needs, so a subsystem nothing mounts passes its own tests and reads
 * {@code PROVEN} in the catalogue. {@code CLAUDE.md} names the question that
 * catches it and also names why it does not get asked — nothing fails.
 *
 * <p>So it is recorded, in the shape of {@link ApiLedger}: computed by a task,
 * reviewed in a diff, and carrying a <b>reason</b> per entry that a person
 * wrote and regeneration preserves. An entry with no reason is the whole
 * product — it is a class that stopped being reachable and nobody decided
 * that it should.
 *
 * <p><b>Read from the built jars, never from the source text.</b> The
 * documented signal is {@code new X(} and it does not work: this codebase
 * writes cross-bundle references fully qualified as a matter of course, so a
 * grep on the simple name reports a mounted subsystem as unreached. Bytecode
 * does not care how the source spelled the name. It also catches the reference
 * that is not a construction at all — a static call, an implemented interface,
 * a field type — which is the difference between asking <i>who builds this</i>
 * and asking <i>does production know this exists</i>. The second is the
 * question.
 *
 * <p>Tests are absent by construction rather than by exclusion: they are not
 * in anybody's jar. That is the entire reason this reads jars.
 *
 * <p><b>It judges classes, so a dead method on a live class is invisible to
 * it.</b> A type whose mounted door calls one of its entry points is reached,
 * and a second entry point beside it that nothing calls — the one whose
 * javadoc calls itself the only way in — passes unremarked. That gap is real
 * and is not closed by making the ledger finer: a per-method record would list
 * most of the store and be read by nobody. It is written down here so the
 * ledger is not mistaken for a guarantee it does not make.
 */
final class ReachLedger {

    /**
     * The reason held for an entry the tool cannot know: written by a person,
     * carried across regeneration, and demanded of anything new.
     */
    static final String MISSING = "REASON MISSING";

    private ReachLedger() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.writeString(out, render(existing(out)), StandardCharsets.UTF_8);
        System.out.println("reach ledger recorded: " + out);
    }

    /** The reasons already recorded, by class name. */
    static Map<String, String> existing(Path ledger) throws IOException {
        Map<String, String> reasons = new HashMap<>();
        if (!Files.exists(ledger)) {
            return reasons;
        }
        for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] split = line.trim().split("\\s{2,}", 2);
            if (split.length == 2) {
                reasons.put(split[0], split[1].trim());
            }
        }
        return reasons;
    }

    static String render(Map<String, String> reasons) throws Exception {
        StringBuilder text = new StringBuilder("""
                # Production classes that no other production class names.
                #
                # GENERATED — do not edit the class list. Re-record with:
                #     ./gradlew :core:harness:reachLedger
                #
                # The REASON is yours and regeneration keeps it. A new entry arrives
                # as REASON MISSING and the build fails until somebody says which of
                # these it is:
                #
                #   an external consumer   — name them; this store's API is used
                #                            outside this repository
                #   reached by a mechanism the ledger cannot see — say which, and
                #                            prefer teaching the ledger over writing
                #                            this down
                #   NOT REACHED            — the defect this file exists to surface:
                #                            built, proven, and mounted by nothing
                #
                # Activators, SPI providers, karaf commands and main classes are
                # reachable and are not listed: they are found from the manifest,
                # from META-INF/services, from their annotations and from their own
                # signature.
                """);
        for (Map.Entry<String, String> entry : unreached().entrySet()) {
            text.append("%-72s %s%n".formatted(entry.getKey(),
                    reasons.getOrDefault(entry.getKey(), MISSING)));
        }
        return text.toString();
    }

    /** Every judged class nothing else in production names, with no reason yet. */
    static Map<String, String> unreached() throws Exception {
        World world = read();
        Map<String, String> found = new TreeMap<>();
        for (String name : world.judged) {
            if (!world.named.contains(name) && !world.mechanised.contains(name)) {
                found.put(name, MISSING);
            }
        }
        return found;
    }

    /** What the jars say: who exists, who is named, who a mechanism reaches. */
    record World(Set<String> judged, Set<String> named, Set<String> mechanised) {
    }

    static World read() throws Exception {
        Set<String> judged = new TreeSet<>();
        Set<String> named = new HashSet<>();
        Set<String> mechanised = new HashSet<>();
        List<Path> jars = staged();
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                mechanisms(file, mechanised);
                for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    byte[] bytes;
                    try (InputStream in = file.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    ClassFile parsed = ClassFile.of(bytes);
                    if (parsed == null || !parsed.own()) {
                        continue;
                    }
                    if (parsed.judgeable()) {
                        judged.add(parsed.topLevel());
                    }
                    if (parsed.isEntryPoint()) {
                        mechanised.add(parsed.topLevel());
                    }
                    for (String reference : parsed.references()) {
                        // A class naming itself, or its own nest, is not a
                        // second party. The question is whether anything ELSE
                        // in production knows this exists.
                        if (!reference.equals(parsed.topLevel())) {
                            named.add(reference);
                        }
                    }
                }
            }
        }
        return new World(judged, named, mechanised);
    }

    /** The jars this runs over, staged by the build under {@code *.reach.jar}. */
    private static List<Path> staged() {
        List<Path> jars = new ArrayList<>();
        Map<String, String> properties = new LinkedHashMap<>();
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.endsWith(".reach.jar")) {
                properties.put(key, System.getProperty(key));
            }
        }
        if (properties.isEmpty()) {
            // Never "found nothing, so nothing is unreachable": a ledger with
            // no jars records an empty world and passes, which is the shape of
            // a ratchet that guards nothing.
            throw new IllegalStateException("no production jars staged — the ledger would "
                    + "record a world with nothing in it and call it clean");
        }
        for (String path : new TreeSet<>(properties.values())) {
            jars.add(Path.of(path));
        }
        return jars;
    }

    /** Registration this store's own code does not perform and the ledger must know. */
    private static void mechanisms(JarFile file, Set<String> into) throws IOException {
        if (file.getManifest() != null) {
            String activator = file.getManifest().getMainAttributes().getValue("Bundle-Activator");
            if (activator != null) {
                into.add(topLevelOf(activator.trim()));
            }
            String main = file.getManifest().getMainAttributes().getValue("Main-Class");
            if (main != null) {
                into.add(topLevelOf(main.trim()));
            }
        }
        for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
            JarEntry entry = entries.nextElement();
            if (!entry.getName().startsWith("META-INF/services/")) {
                continue;
            }
            try (InputStream in = file.getInputStream(entry)) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .toList()) {
                    String name = line.split("#")[0].trim();
                    if (!name.isEmpty()) {
                        into.add(topLevelOf(name));
                    }
                }
            }
            // The service INTERFACE is named by the file's own name, and a
            // provider nobody names is still reached through it.
            into.add(topLevelOf(entry.getName()
                    .substring("META-INF/services/".length()).trim()));
        }
    }

    static String topLevelOf(String binaryName) {
        String name = binaryName.replace('/', '.');
        int nest = name.indexOf('$');
        return nest < 0 ? name : name.substring(0, nest);
    }
}
