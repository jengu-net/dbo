package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promise.Proofs;
import cloud.jengu.dbo.promise.Promise;
import cloud.jengu.dbo.promise.Registry;
import cloud.jengu.dbo.promise.Report;
import cloud.jengu.dbo.promises.DboPromises;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The catalogue's prose forms, generated from the composed model
 * (REQ-DBO-PRM-PROJECTION-IS-GENERATED) — never a second source.
 *
 * <p>Two renderings from one model: the full report (areas, classifications,
 * coverage folds — what a build's results page leads with), and the
 * requirement-table block spliced into {@code req-catalogue.md} between
 * visible markers, in the table shape readers already know plus what the
 * hand-written tables never had — status and proving sites.
 *
 * <p>Run as {@code ./gradlew :core:harness:promiseReport} (report) and
 * {@code :core:harness:promiseProjection} (rewrite the catalogue block);
 * {@link PromiseCatalogueTest} regenerates the block in memory on every
 * build and fails on any difference, so a hand-edit and a stale projection
 * are the same refusal.
 */
public final class PromiseProjection {

    static final String BEGIN = "<!-- promise:begin — generated from the promise catalogue;"
            + " do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->";
    static final String END = "<!-- promise:end -->";

    private PromiseProjection() {
    }

    /** args: {@code report <out.md>} | {@code project <req-catalogue.md>}. */
    public static void main(String[] args) throws Exception {
        ClassLoader loader = PromiseProjection.class.getClassLoader();
        Registry.Model model = Registry.load(loader).model(Proofs.load(loader));
        switch (args[0]) {
            case "report" -> {
                Path out = Path.of(args[1]);
                Files.createDirectories(out.getParent());
                Files.writeString(out, Report.render(model));
                System.out.println("promise report: " + out);
            }
            case "project" -> {
                Path catalogue = Path.of(args[1]);
                Files.writeString(catalogue, projected(model, Files.readString(catalogue)));
                System.out.println("promise projection refreshed: " + catalogue);
            }
            default -> throw new IllegalArgumentException("report|project, not " + args[0]);
        }
    }

    /** The whole catalogue file with the generated block replaced. */
    static String projected(Registry.Model model, String catalogueFile) {
        int begin = catalogueFile.indexOf(BEGIN);
        int end = catalogueFile.indexOf(END);
        if (begin < 0 || end < 0) {
            throw new IllegalStateException("req-catalogue.md carries no promise markers — "
                    + "the generated block has nowhere to live");
        }
        return catalogueFile.substring(0, begin) + BEGIN + "\n" + block(model)
                + catalogueFile.substring(end);
    }

    /** The generated tables: promise-managed areas, grouped by code prefix. */
    static String block(Registry.Model model) {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("SHAPE", "## SHAPE — shape versioning\n");
        sections.put("PDI", "## PDI — personal-data isolation\n");
        Map<String, StringBuilder> tables = new TreeMap<>();
        for (Promise promise : model.promises()) {
            String code = model.codeOf(promise);
            for (String prefix : sections.keySet()) {
                if (code.startsWith("REQ-DBO-" + prefix + "-")) {
                    tables.computeIfAbsent(prefix, p -> new StringBuilder())
                            .append("| ").append(code)
                            .append(" | ").append(promise.text())
                            .append(" | ").append(model.statusOf(promise))
                            .append(" | ").append(String.join("<br>", model.citing(promise)
                                    .stream().sorted().toList()))
                            .append(" |\n");
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> section : sections.entrySet()) {
            out.append('\n').append(section.getValue())
                    .append("\n| REQ | Promise | Status | Proven by |\n|---|---|---|---|\n")
                    .append(tables.getOrDefault(section.getKey(), new StringBuilder()));
            gapsFor(model, section.getKey(), out);
        }
        return out.append('\n').toString();
    }

    /** Gaps declared by classifications whose promises live in this section. */
    private static void gapsFor(Registry.Model model, String prefix, StringBuilder out) {
        for (Promise promise : model.promises()) {
            if (!promise.gap()) {
                continue;
            }
            boolean besideThisSection = model.declaring(promise).stream()
                    .flatMap(c -> c.promises().stream())
                    .anyMatch(p -> !p.gap() && (p instanceof DboPromises named)
                            && named.code().startsWith("REQ-DBO-" + prefix + "-"));
            if (besideThisSection) {
                out.append("| ").append(model.codeOf(promise))
                        .append(" | *gap: ").append(promise.text())
                        .append("* | GAP |  |\n");
            }
        }
    }
}
