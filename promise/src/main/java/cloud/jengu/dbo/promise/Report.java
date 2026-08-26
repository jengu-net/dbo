package cloud.jengu.dbo.promise;

import java.util.List;
import java.util.Map;

/**
 * The composed report, rendered as markdown: per-area sections, each
 * classification's promise table with derived status and citing sites, gap
 * lines verbatim. This is the shape the catalogue projection consumes — the
 * prose form of the graph, never a second source.
 */
public final class Report {

    private Report() {
    }

    public static String render(Registry.Model model) {
        StringBuilder out = new StringBuilder("# Promise report\n");
        for (Area area : model.areas()) {
            out.append("\n## ").append(area.code()).append(" — ").append(area.title())
                    .append("\n\n").append(line(model.coverage(area))).append('\n');
            for (Classified classified : area.covers()) {
                section(out, model, classified);
            }
        }
        List<Classified> ungrouped = model.classifications().stream()
                .filter(c -> model.areas().stream().noneMatch(a -> a.covers().contains(c)))
                .toList();
        if (!ungrouped.isEmpty()) {
            out.append("\n## (no area)\n");
            for (Classified classified : ungrouped) {
                section(out, model, classified);
            }
        }
        return out.toString();
    }

    private static void section(StringBuilder out, Registry.Model model, Classified classified) {
        out.append("\n### ").append(classified.code()).append(" — ")
                .append(classified.title()).append("\n\n")
                .append(line(model.coverage(classified)))
                .append("\n\n| promise | status | proven by |\n|---|---|---|\n");
        for (Promise promise : classified.promises()) {
            out.append("| ").append(model.codeOf(promise));
            if (promise.gap()) {
                out.append(" — *").append(promise.text()).append('*');
            }
            out.append(" | ").append(model.statusOf(promise))
                    .append(" | ").append(String.join("<br>", model.citing(promise)))
                    .append(" |\n");
        }
    }

    private static String line(Map<PromiseStatus, Long> coverage) {
        long total = coverage.values().stream().mapToLong(Long::longValue).sum();
        long proven = coverage.getOrDefault(PromiseStatus.PROVEN, 0L)
                + coverage.getOrDefault(PromiseStatus.ASSURED, 0L);
        return "coverage: " + proven + "/" + total + " — " + coverage;
    }
}
