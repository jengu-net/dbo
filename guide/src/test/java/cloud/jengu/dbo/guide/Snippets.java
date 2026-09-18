package cloud.jengu.dbo.guide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The published commands, run as published.
 *
 * <p>Each snippet is a file the chapter includes whole, so the text a reader
 * copies and the text that runs are the same bytes — not a region marker that
 * can be renamed, and not a Java translation of a curl command, which would be
 * two answers to one question.
 *
 * <p><b>What this owns that the shell script did not: the state.</b> The
 * snippets share values — a token, a patient's id, a run's context — and in one
 * long script that sharing is positional: a block works because of where it
 * sits. That is how a region came to read a variable a later block defined, and
 * the failure was an unbound name forty steps in rather than anything about the
 * store. Here a snippet is given what it needs by name and hands back what it
 * produces, so the data flow is declared rather than implied by line order.
 */
final class Snippets {

    private static final Path DIRECTORY =
            Path.of("..", "docs", "guide", "examples", "snippets");

    /** What the snippets so far have produced, by the name they are known by. */
    private final Map<String, String> known = new LinkedHashMap<>();

    /** Where a snippet's visible output ends and its leftover state begins. */
    private static final String STATE = "__SNIPPET_STATE__";

    private static final java.util.regex.Pattern DECLARED =
            java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=(.*)");

    /** One snippet's result: what it printed, and how it ended. */
    record Ran(String out, String err, int status) {

        /** The output with trailing newlines trimmed, which is what a reader sees. */
        String text() {
            return out.strip();
        }

        /** The last line, for the many snippets whose answer is one line. */
        String lastLine() {
            String[] lines = text().split("\n");
            return lines[lines.length - 1];
        }
    }

    private static Path resolve(String snippet) {
        Path file = DIRECTORY.resolve(snippet + ".sh");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("no such snippet: " + file.toAbsolutePath()
                    + " — a chapter including it would fail the site build, so this is "
                    + "the same mistake caught earlier");
        }
        return file;
    }

    void remember(String name, String value) {
        known.put(name, value);
    }

    String recall(String name) {
        String value = known.get(name);
        if (value == null) {
            // Named rather than empty. A snippet that silently interpolated an
            // empty string would fail against the store with a confusing
            // answer, which is the shape of failure this class exists to stop.
            throw new IllegalStateException("no snippet has produced '" + name + "' yet — "
                    + "either it runs later than this one, or nothing captures it. "
                    + "Known: " + known.keySet());
        }
        return value;
    }

    /**
     * Runs shell that is NOT part of the guide, with the same state.
     *
     * <p>Some steps need a situation before they can show anything: a record
     * somebody else has already moved, a spec file appearing on disk. That
     * arranging is not what the chapter publishes, and writing it in Java
     * would translate curl into something a reader never sees — so it stays
     * shell, and only the assertion becomes Java.
     */
    Ran sh(String script) throws IOException, InterruptedException {
        ProcessBuilder bash = new ProcessBuilder("bash", "-euo", "pipefail", "-c", script);
        bash.environment().putAll(known);
        bash.directory(Path.of("..").toFile());
        Process process = bash.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(5, TimeUnit.MINUTES);
        return new Ran(out, err, process.exitValue());
    }

    /**
     * Runs a published snippet with what it needs in the environment.
     *
     * <p>The snippet is run by {@code bash} exactly as it is written, so a
     * reader pasting it into their own shell gets what the test got.
     */
    Ran run(String snippet, String... alreadyInTheShell)
            throws IOException, InterruptedException {
        Path file = resolve(snippet);
        // SOURCED, not executed, and then asked what it left behind. A
        // snippet like the one that fetches tokens sets variables and prints
        // nothing; run in its own process those values die with it, and the
        // next chapter's command would interpolate an empty string.
        //
        // The file itself is unchanged — it is sourced verbatim, so what runs
        // is what the chapter publishes. What is appended is the asking.
        // The capture asks for names and reads them back one at a time rather
        // than parsing `declare -p`, whose output format is not the same
        // everywhere — on this machine it prints bare NAME=value, so a regex
        // written for `declare -- NAME="value"` matched nothing, and a grep
        // that matches nothing exits 1, which `set -e` turned into a snippet
        // that had "failed". Two layers of inference about somebody else's
        // output format, to learn something bash can simply be asked.
        // What the snippet defined, by comparing before with after rather
        // than by insisting names look a certain way — and the comparison is
        // on name AND value, so a snippet that changes something it inherited
        // is still seen, while the several hundred variables this process
        // happens to have been started with are not reported as the guide's
        // doing. Snapshotting the names alone was the first attempt, and it
        // left every inherited name in the record: harmless until a failure
        // printed them, which is how it was found. The published commands
        // set `id` and `bones` as readily as HOSPITAL, and a runner that only
        // captured shouting ones would quietly drop half of them — or, worse,
        // make the documentation spell its variables to suit the test.
        //
        // The file is sourced verbatim, so what runs is what the chapter
        // publishes; the snapshotting is around it.
        // What a reader has in their terminal by the time they reach this
        // block. A snippet that calls a function an earlier snippet defined —
        // `claims`, `token`, `token_exchange` — works in their shell because
        // they ran that one first, and fails here unless this says so. Naming
        // the chain is the honest form of it: a snippet whose prerequisites
        // are not named is one a reader cannot run either, which is how a
        // published command calling an undefined function went unnoticed.
        java.util.List<String> command = new java.util.ArrayList<>(
                java.util.List.of("snippet", file.toAbsolutePath().toString()));
        for (String earlier : alreadyInTheShell) {
            command.add(resolve(earlier).toAbsolutePath().toString());
        }
        java.util.List<String> invocation = new java.util.ArrayList<>(
                java.util.List.of("bash", "-euo", "pipefail", "-c",
                "__target=\"$1\"; shift; "
                        + "for __earlier in \"$@\"; do source \"$__earlier\" >/dev/null; done; "
                        + "__before=$(for __n in $(compgen -v); do "
                        + "  printf '%s=%s\\n' \"$__n\" \"${!__n-}\"; done); "
                        + "source \"$__target\"; "
                        + "printf '\\n" + STATE + "\\n'; "
                        + "for __name in $(compgen -v); do "
                        + "  case \"$__name\" in __*|BASH*|FUNCNAME|PIPESTATUS|_) continue ;; esac; "
                        + "  __value=\"${!__name-}\"; "
                        + "  case \"$__value\" in *$'\\n'*) continue ;; esac; "
                        + "  case $'\\n'\"$__before\"$'\\n' in "
                        + "    *$'\\n'\"$__name=$__value\"$'\\n'*) continue ;; esac; "
                        + "  printf '%s=%s\\n' \"$__name\" \"$__value\"; "
                        + "done"));
        invocation.addAll(command);
        ProcessBuilder bash = new ProcessBuilder(invocation);
        bash.environment().putAll(known);
        // The repository root, because a snippet that names a compose file
        // names it the way a reader would: from where they cloned.
        bash.directory(Path.of("..").toFile());
        Process process = bash.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException(snippet + " did not finish");
        }
        // Split what the reader would see from what the next snippet needs.
        int mark = out.indexOf(STATE);
        String visible = mark < 0 ? out : out.substring(0, mark);
        if (mark >= 0) {
            for (String line : out.substring(mark + STATE.length()).split("\n")) {
                java.util.regex.Matcher m = DECLARED.matcher(line.strip());
                if (m.matches()) {
                    known.put(m.group(1), m.group(2));
                }
            }
        }
        return new Ran(visible, err, process.exitValue());
    }
}
