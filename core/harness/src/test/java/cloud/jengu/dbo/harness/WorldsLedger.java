package cloud.jengu.dbo.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Every harness class that builds a runtime of its own.
 *
 * <p>A runtime costs about thirty-four seconds to bring up, and a class that
 * builds one to run a few seconds of assertions pays that on every run of the
 * suite. {@link SharedTenants} exists so that a class which only needs
 * somewhere to write does not, and the shared-world rule says which tests may
 * still take a world of their own: those about the tenant itself, the
 * container, a tampered row, a first boot, a deployment-wide sweep, or
 * an assertion about a whole plane.
 *
 * <p>Nothing enforced the rule, so the count grew. This records it in the
 * shape of {@link ReachLedger}: computed from the sources, reviewed in a diff,
 * and carrying a <b>reason</b> per entry that a person wrote and regeneration
 * preserves. A new class arrives as {@code REASON MISSING} and the build fails
 * until somebody says which of the allowed reasons it is.
 *
 * <p>The classes that predate the ledger are recorded as {@code UNDECIDED},
 * and their number is a ratchet: the header carries how many are allowed,
 * regeneration lowers it whenever the actual count is lower, and the build
 * fails when the actual count is higher. So the migration's worklist can only
 * shrink, and a new class cannot hide among the old ones.
 *
 * <p>The signal is the construction of a {@code TenantRuntimeManager}, read
 * from the source text. A class that takes a tenant from the shared
 * runtime never writes that; a class that builds a world of its own always
 * does.
 */
final class WorldsLedger {

    static final String MISSING = "REASON MISSING";

    static final String UNDECIDED = "UNDECIDED";

    static final String ALLOWANCE = "undecided-allowed:";

    // In two halves, so this class does not answer its own scan.
    private static final String SIGNAL = "new " + "TenantRuntimeManager(";

    private WorldsLedger() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Path sources = Path.of(args[1]);
        // A ledger written for the first time records what predates it as
        // the worklist; from then on a class it has never seen has to say why.
        String fallback = Files.exists(out) ? MISSING : UNDECIDED;
        Files.writeString(out, render(existing(out), allowance(out), ownWorlds(sources), fallback),
                StandardCharsets.UTF_8);
        System.out.println("worlds ledger recorded: " + out);
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

    /** How many undecided entries the ledger allows, or none when it has never said. */
    static int allowance(Path ledger) throws IOException {
        if (!Files.exists(ledger)) {
            return Integer.MAX_VALUE;
        }
        for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            if (line.startsWith("# " + ALLOWANCE)) {
                return Integer.parseInt(line.substring(("# " + ALLOWANCE).length()).trim());
            }
        }
        return Integer.MAX_VALUE;
    }

    static long undecided(Map<String, String> reasons) {
        return reasons.values().stream().filter(UNDECIDED::equals).count();
    }

    /** Every harness class whose source constructs a runtime, by simple name. */
    static TreeSet<String> ownWorlds(Path sources) throws IOException {
        TreeSet<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!file.toString().endsWith(".java")) {
                    continue;
                }
                String name = file.getFileName().toString().replace(".java", "");
                if (name.equals("SharedTenants")) {
                    continue;
                }
                if (Files.readString(file, StandardCharsets.UTF_8).contains(SIGNAL)) {
                    found.add(name);
                }
            }
        }
        if (found.isEmpty()) {
            // Never "found nothing, so nothing builds a world": a scan that
            // stopped reading the suite would record it as migrated.
            throw new IllegalStateException("no harness class constructs a runtime under "
                    + sources + " — the scan has stopped reading the suite");
        }
        return found;
    }

    static String render(Map<String, String> reasons, int allowance, TreeSet<String> classes,
            String fallback) {
        Map<String, String> next = new HashMap<>();
        for (String name : classes) {
            next.put(name, reasons.getOrDefault(name, fallback));
        }
        long undecided = undecided(next);
        long allowed = Math.min(allowance, undecided);
        StringBuilder text = new StringBuilder("""
                # Harness classes that build a runtime of their own.
                #
                # GENERATED — do not edit the class list. Re-record with:
                #     ./gradlew :core:harness:worldsLedger
                #
                # The REASON is yours and regeneration keeps it. A new entry arrives
                # as REASON MISSING and the build fails until it says which of these
                # it is:
                #
                #   lifecycle   — the test is about the tenant coming up, going down,
                #                 or being held out of service
                #   container   — it needs the OSGi container or the distribution
                #   tampering   — it alters what the store holds behind its back
                #   first boot  — it needs a runtime nothing has touched
                #   sweep       — it runs a deployment-wide pass (shapesRound,
                #                 syncRound, scanOnce), which would visit every
                #                 tenant a shared runtime holds
                #   whole plane — it asserts about an entire substrate, not about
                #                 its own tenant, so a shared one would make the
                #                 claim about every other class's work too
                #   UNDECIDED   — predates the ledger, and is the migration's worklist
                #
                # The number of UNDECIDED entries may only fall. Regeneration lowers the
                # allowance to the actual count; the build fails above it.
                #
                """);
        text.append("# ").append(ALLOWANCE).append(' ').append(allowed).append('\n');
        for (String name : classes) {
            text.append("%-56s %s%n".formatted(name, next.get(name)));
        }
        return text.toString();
    }
}
