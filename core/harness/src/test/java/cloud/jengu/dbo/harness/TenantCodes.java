package cloud.jengu.dbo.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which tenant code each harness class declares.
 *
 * <p>One Postgres serves the whole suite and a tenant's database name is
 * derived from its code, so two classes naming one tenant address one
 * database. Whichever provisions first stores its bootstrap secret; the second
 * finds the database already there, holds a different secret, and is refused
 * as {@code invalid_client} — in a full run only, decided by which class ran
 * first, and never when either is run alone.
 *
 * <p>{@link UntilServed} refuses this at the moment of the second scan, which
 * is the better failure because it names both classes. But it only sees codes
 * that reach it as arguments, and it only fires when the two classes happen to
 * run in the same JVM. This reads the sources instead, so a collision is
 * answered by whoever wrote it rather than by whoever next runs the suite.
 *
 * <p><b>What it cannot read, it refuses.</b> A scan that skips what it does
 * not understand is one that quietly stops covering things, so a spec written
 * under a name this cannot resolve fails and says which expression stopped it.
 * The fix is to write the code where it can be seen — a literal, a field, a
 * local, or an argument at the call site.
 */
final class TenantCodes {

    /** `Files.writeString(<dir>.resolve(<expr>), …` — a spec being declared. */
    private static final Pattern WRITE = Pattern.compile(
            "writeString\\(\\s*\\w+\\.resolve\\(\\s*([^)]+?)\\s*\\)\\s*,", Pattern.DOTALL);

    /** A name bound to a literal, whether a field or a local. */
    private static final Pattern BOUND = Pattern.compile(
            "String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"");

    /** `for (String x : List.of("a", "b"))` — several specs from one write. */
    private static final Pattern OVER_A_LIST = Pattern.compile(
            "for\\s*\\(\\s*String\\s+(\\w+)\\s*:\\s*List\\.of\\(([^)]*)\\)\\s*\\)");

    /**
     * A method that takes the code and writes the spec for its callers.
     *
     * <p>`throws` is matched across a line break because a signature long
     * enough to wrap is exactly the signature a helper like this has.
     */
    private static final Pattern METHOD = Pattern.compile(
            "(?:private|static|public|protected|final|\\s)+[\\w.<>\\[\\]]+\\s+(\\w+)"
                    + "\\s*\\(([^)]*)\\)\\s*(?:throws [\\w.,\\s]+?)?\\{");

    private static final Pattern LITERAL = Pattern.compile("^\"(.+)\\.json\"$");
    private static final Pattern NAMED = Pattern.compile("^(\\w+)\\s*\\+\\s*\"\\.json\"$");

    /** A code, and the file that declared it. */
    record Declaration(String code, String file, String how) {}

    private TenantCodes() {}

    /** Every declaration the harness sources make, and what stopped a read. */
    static Reading read(Path harness) throws IOException {
        List<Declaration> declared = new ArrayList<>();
        Map<String, List<String>> unreadable = new TreeMap<>();
        try (Stream<Path> files = Files.walk(harness)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                readOne(file, declared, unreadable);
            }
        }
        return new Reading(declared, unreadable);
    }

    record Reading(List<Declaration> declared, Map<String, List<String>> unreadable) {

        /** Codes declared by more than one class, with the classes. */
        Map<String, java.util.Set<String>> shared() {
            Map<String, java.util.Set<String>> byCode = new TreeMap<>();
            for (Declaration d : declared) {
                byCode.computeIfAbsent(d.code(), c -> new TreeSet<>()).add(d.file());
            }
            byCode.values().removeIf(files -> files.size() < 2);
            return byCode;
        }
    }

    private static void readOne(Path file, List<Declaration> declared,
            Map<String, List<String>> unreadable) throws IOException {
        String src = Files.readString(file);
        String name = file.getFileName().toString();

        Map<String, String> bound = new LinkedHashMap<>();
        Matcher m = BOUND.matcher(src);
        while (m.find()) {
            bound.put(m.group(1), m.group(2));
        }
        Map<String, List<String>> lists = new LinkedHashMap<>();
        m = OVER_A_LIST.matcher(src);
        while (m.find()) {
            lists.put(m.group(1), literalsIn(m.group(2)));
        }

        m = WRITE.matcher(src);
        while (m.find()) {
            String expr = m.group(1).trim();
            Matcher literal = LITERAL.matcher(expr);
            if (literal.matches()) {
                declared.add(new Declaration(literal.group(1), name, "written by name"));
                continue;
            }
            Matcher named = NAMED.matcher(expr);
            if (!named.matches()) {
                unreadable.computeIfAbsent(name, f -> new ArrayList<>()).add(expr);
                continue;
            }
            String reference = named.group(1);
            if (bound.containsKey(reference)) {
                declared.add(new Declaration(bound.get(reference), name, reference));
            } else if (lists.containsKey(reference)) {
                for (String code : lists.get(reference)) {
                    declared.add(new Declaration(code, name, reference));
                }
            } else {
                List<String> fromCallers = throughTheMethodAt(src, m.start(), reference, bound);
                if (fromCallers.isEmpty()) {
                    unreadable.computeIfAbsent(name, f -> new ArrayList<>()).add(expr);
                } else {
                    for (String code : fromCallers) {
                        declared.add(new Declaration(code, name, reference + ", at its callers"));
                    }
                }
            }
        }
    }

    /**
     * A spec written by a helper, read from what its callers hand it.
     *
     * <p>The code is a parameter here, so the only place it exists is the call
     * site. Which method and which of its parameters comes from the signature
     * above the write; what is passed there comes from the calls below it.
     */
    private static List<String> throughTheMethodAt(String src, int write, String parameter,
            Map<String, String> bound) {
        String method = null;
        int position = -1;
        Matcher m = METHOD.matcher(src);
        while (m.find() && m.start() < write) {
            List<String> parameters = new ArrayList<>();
            for (String declaredParameter : m.group(2).split(",")) {
                String[] parts = declaredParameter.trim().split("\\s+");
                parameters.add(parts.length > 1 ? parts[parts.length - 1] : "");
            }
            if (parameters.contains(parameter)) {
                method = m.group(1);
                position = parameters.indexOf(parameter);
            }
        }
        if (method == null) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        Matcher call = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(src);
        while (call.find()) {
            List<String> arguments = argumentsFrom(src, call.end() - 1);
            if (arguments.size() <= position) {
                continue;
            }
            String argument = arguments.get(position).trim();
            if (argument.startsWith("\"") && argument.endsWith("\"")) {
                codes.add(argument.substring(1, argument.length() - 1));
            } else if (bound.containsKey(argument)) {
                codes.add(bound.get(argument));
            }
        }
        return codes;
    }

    /**
     * One call's arguments, split where the commas actually separate them.
     *
     * <p>Counted rather than matched: an argument is regularly a call of its
     * own — {@code nodeServing("north", Steps.of(ASSAY_V2), NORTH)} — and a
     * pattern that stops at the first closing bracket reads two arguments
     * there and silently misses the third, which is the one carrying the code.
     */
    private static List<String> argumentsFrom(String src, int open) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = open + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                inString = c != '"' || src.charAt(i - 1) == '\\';
            } else if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ')') {
                if (depth == 0) {
                    arguments.add(current.toString());
                    return arguments;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        return List.of();
    }

    private static List<String> literalsIn(String arguments) {
        List<String> literals = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(arguments);
        while (m.find()) {
            literals.add(m.group(1));
        }
        return literals;
    }
}
