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
    static final String STORY_BEGIN = "<!-- story:begin — generated from the promise catalogue;"
            + " do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->";
    static final String STORY_END = "<!-- story:end -->";

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
                if (args.length > 2) {
                    Path stories = Path.of(args[2]);
                    for (cloud.jengu.dbo.promise.Story story : stories(model)) {
                        Path file = storyFile(stories, model, story);
                        Files.writeString(file, projectedStory(model, story,
                                Files.readString(file)));
                        System.out.println("story projection refreshed: " + file);
                    }
                }
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

    /** Every story the catalogue declares, in declaration order. */
    static java.util.List<cloud.jengu.dbo.promise.Story> stories(Registry.Model model) {
        return model.classifications().stream()
                .filter(c -> c instanceof cloud.jengu.dbo.promise.Story)
                .map(c -> (cloud.jengu.dbo.promise.Story) c).toList();
    }

    /**
     * Where a story's markdown lives: the code, lowercased, in the stories
     * directory. A story constant with no scene to tell is refused rather
     * than projected into nothing.
     */
    static Path storyFile(Path stories, Registry.Model model, cloud.jengu.dbo.promise.Story story) {
        Path file = stories.resolve(story.code().toLowerCase(java.util.Locale.ROOT) + ".md");
        if (!Files.exists(file)) {
            throw new IllegalStateException("story " + story.code() + " is declared and has no "
                    + "scene at " + file + " — a story is a constant AND a page, or it is neither");
        }
        return file;
    }

    /** The story file with its generated joins block replaced. */
    static String projectedStory(Registry.Model model, cloud.jengu.dbo.promise.Story story,
            String storyFile) {
        int begin = storyFile.indexOf(STORY_BEGIN);
        int end = storyFile.indexOf(STORY_END);
        if (begin < 0 || end < 0) {
            throw new IllegalStateException(story.code() + " carries no story markers — the "
                    + "generated joins have nowhere to live");
        }
        return storyFile.substring(0, begin) + STORY_BEGIN + "\n" + storyBlock(model, story)
                + storyFile.substring(end);
    }

    /**
     * A story's joins, projected: the promises it declares, each with the
     * status its own citations give it. A story claims no evidence, so a
     * story whose every leg is planned says so in one line rather than
     * listing legs a reader would take as proven.
     */
    static String storyBlock(Registry.Model model, cloud.jengu.dbo.promise.Story story) {
        if (story.promises().isEmpty()) {
            throw new IllegalStateException(story.code() + " declares no promise — a story "
                    + "with no joins is prose, and belongs as a step of one that has them");
        }
        StringBuilder out = new StringBuilder("\n| Promise | Says | Status |\n|---|---|---|\n");
        boolean anyProven = false;
        for (Promise promise : story.promises()) {
            var status = model.statusOf(promise);
            anyProven |= status == cloud.jengu.dbo.promise.PromiseStatus.PROVEN
                    || status == cloud.jengu.dbo.promise.PromiseStatus.ASSURED;
            out.append("| `").append(model.codeOf(promise)).append("` | ")
                    .append(promise.text()).append(" | ").append(status).append(" |\n");
        }
        Map<cloud.jengu.dbo.promise.PromiseStatus, Long> coverage = model.coverage(story);
        out.append("\n");
        if (!anyProven) {
            out.append("**Unproven.** Every leg this story rests on is planned; nothing here "
                    + "is evidence yet.\n");
        } else {
            out.append("Coverage: ").append(coverage).append(" — a leg marked PLANNED cites "
                    + "a promise that exists and is not yet cited by any test.\n");
        }
        return out.toString();
    }

    /** The generated tables: promise-managed areas, grouped by code prefix. */
    static String block(Registry.Model model) {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("SHAPE", "## SHAPE — shape versioning\n");
        sections.put("PDI", "## PDI — personal-data isolation\n");
        // PROC migrated whole (2026-08-27): every process/step/run promise
        // is now a catalogue constant. Six carry no citation yet and read
        // PLANNED with a TODO on the constant — that is the honest state,
        // not a defect in the migration.
        sections.put("PROC", "## PROC — distributed work\n");
        // The rest of the catalogue, migrated whole the same day: every
        // remaining hand-written REQ is now a constant. Most read PLANNED
        // with a "TODO: prove it in a test" on the constant — citation is a
        // deliberately separate pass, not part of this port.
        sections.put("CORE", "## CORE — object engine\n");
        sections.put("CONT", "## CONT — container & embedding\n");
        sections.put("TEN", "## TEN — tenancy & isolation\n");
        sections.put("AUTH", "## AUTH — tenant authority & surface protection\n");
        sections.put("POL", "## POL — tenant policies (audit & write discipline)\n");
        sections.put("ZONE", "## ZONE — jurisdiction overlay\n");
        sections.put("VER", "## VER — version plurality (personalities)\n");
        sections.put("SRCH", "## SRCH — search\n");
        sections.put("FEED", "## FEED — feeds, pagination, synchronization\n");
        sections.put("EVT", "## EVT — eventing & subscriptions\n");
        sections.put("WF", "## WF — durable work & planes\n");
        sections.put("SCAL", "## SCAL — scaling & routing\n");
        sections.put("TERM", "## TERM — terminology\n");
        sections.put("SYNC", "## SYNC — canonical content dependencies\n");
        sections.put("VAL", "## VAL — coded-value validation\n");
        sections.put("OPS", "## OPS — operations\n");
        sections.put("MNT", "## MNT — maintenance\n");
        sections.put("PRM", "## PRM — promise (requirements as code)\n");
        sections.put("SCIM", "## SCIM — staff provisioning surface\n");
        sections.put("IDN", "## IDN — identification\n");
        Map<String, StringBuilder> tables = new TreeMap<>();
        // A promise whose area has no section here used to vanish: the loop
        // below matched no prefix, nothing was appended, and the projection and
        // the model agreed perfectly about a catalogue missing an entire
        // capability. Found by adding six identification promises and watching
        // the rendered document not mention them.
        java.util.List<String> homeless = new java.util.ArrayList<>();
        for (Promise promise : model.promises()) {
            String code = model.codeOf(promise);
            if (sections.keySet().stream().noneMatch(p -> code.startsWith("REQ-DBO-" + p + "-"))) {
                homeless.add(code);
            }
        }
        if (!homeless.isEmpty()) {
            throw new IllegalStateException("these promises belong to no section, so the "
                    + "projection would render a catalogue that silently omits them — add "
                    + "the area above: " + homeless);
        }
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
