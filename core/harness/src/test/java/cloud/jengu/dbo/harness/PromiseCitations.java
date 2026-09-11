package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Coded;
import cloud.jengu.dbo.promise.Registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every promise code spelled out in the tree that no catalogue declares.
 *
 * <p>The citation that carries weight is {@code @Proving}, and renaming a
 * constant breaks all of those at once — that half is the compiler's and needs
 * no help. What it cannot reach is the far larger body of mentions in javadoc
 * and in the specification, where a code is only characters. There a name can
 * be stale, or can never have existed at all, and nothing says so.
 *
 * <p>Modelled on {@link ReachLedger}, and for the same reason: what the scan
 * finds is not all defect, so the answer is recorded rather than guessed. A
 * new entry arrives as REASON MISSING and the build fails until somebody says
 * which it is.
 */
final class PromiseCitations {

    /** A code is a namespace and the constant's name, so there is one spelling. */
    private static final Pattern UNWRAP =
            Pattern.compile("-\\s*\\n\\s*(?:\\*|//)?\\s*(?=[A-Z0-9]+\\b)");

    private PromiseCitations() {
    }

    static Set<String> declared(ClassLoader loader) {
        Set<String> codes = new LinkedHashSet<>();
        for (Class<?> catalogue : Registry.load(loader).catalogues()) {
            for (Object constant : catalogue.getEnumConstants()) {
                codes.add(((Coded) constant).code());
            }
        }
        return codes;
    }

    static Pattern citation(ClassLoader loader) {
        Set<String> namespaces = new LinkedHashSet<>();
        for (Class<?> catalogue : Registry.load(loader).catalogues()) {
            Catalogue marker = catalogue.getAnnotation(Catalogue.class);
            if (marker != null) {
                namespaces.add(marker.namespace());
            }
        }
        return Pattern.compile(
                "\\b(?:" + String.join("|", namespaces) + ")-[A-Z0-9]+(?:-[A-Z0-9]+)*");
    }

    /** Code to the one file that first spells it, for a reader who has to go and look. */
    static Map<String, String> unresolved(Path root, ClassLoader loader) throws IOException {
        Set<String> declared = declared(loader);
        Pattern cited = citation(loader);
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path file : tree.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(file).toString();
                String name = file.getFileName().toString();
                // Working documents are exempt for the reason the branding check
                // exempts them: they carry a topic between its issues and its
                // concepts, and a code proposed there may not exist yet.
                //
                // Generated files are exempt because a citation is not authored
                // in one: tools/dbo-conventions and CLAUDE.md are written by
                // generateSkills and req-catalogue.md by the projection, so a
                // bad code there is a bad code in the source it came from, said
                // twice. Reading them also made this task consume another's
                // output, which Gradle refuses as an undeclared dependency.
                if (rel.contains("build/") || rel.startsWith(".git")
                        || rel.startsWith("docs/tasks/") || rel.startsWith("config/")
                        || rel.startsWith("tools/dbo-conventions/")
                        || rel.equals("CLAUDE.md")
                        || rel.equals("docs/arc42-006-runtime/req-catalogue.md")
                        || !(name.endsWith(".java") || name.endsWith(".md")
                             || name.endsWith(".kts"))) {
                    continue;
                }
                String text = UNWRAP.matcher(Files.readString(file)).replaceAll("-");
                Matcher m = cited.matcher(text);
                while (m.find()) {
                    // `REQ-DBO-SHAPE-*` names a family on purpose, not a promise.
                    if (text.startsWith("-*", m.end()) || declared.contains(m.group())) {
                        continue;
                    }
                    found.putIfAbsent(m.group(), rel);
                }
            }
        }
        return found;
    }

    static Map<String, String> existing(Path ledger) throws IOException {
        Map<String, String> recorded = new LinkedHashMap<>();
        if (!Files.exists(ledger)) {
            return recorded;
        }
        for (String line : Files.readAllLines(ledger)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s{2,}", 2);
            recorded.put(parts[0], parts.length > 1 ? parts[1].strip() : "REASON MISSING");
        }
        return recorded;
    }

    public static void main(String[] args) throws Exception {
        Path ledger = Path.of(args[0]);
        Path root = Path.of(System.getProperty("dbo.repo.root"));
        Map<String, String> recorded = existing(ledger);
        Map<String, String> now = unresolved(root, PromiseCitations.class.getClassLoader());

        List<String> header = Files.exists(ledger)
                ? Files.readAllLines(ledger).stream().takeWhile(l -> l.isEmpty() || l.startsWith("#"))
                        .toList()
                : List.of();
        StringBuilder out = new StringBuilder(String.join("\n", header));
        if (!header.isEmpty()) {
            out.append("\n");
        }
        int width = now.keySet().stream().mapToInt(String::length).max().orElse(0) + 2;
        for (Map.Entry<String, String> e : now.entrySet()) {
            out.append(String.format("%-" + width + "s%s%n",
                    e.getKey(), recorded.getOrDefault(e.getKey(), "REASON MISSING")));
        }
        Files.writeString(ledger, out.toString());
        System.out.println("recorded " + now.size() + " unresolved citations");
    }
}
