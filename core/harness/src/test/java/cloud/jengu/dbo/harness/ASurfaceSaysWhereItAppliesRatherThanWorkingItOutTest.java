package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's door declares which tenants it is for, or it is on this list.
 *
 * <p>The rule the lifecycle points exist for is that <b>no activity derives
 * its own applicability</b>: it declares a selector over the facts a tenant
 * publishes, or it applies to every tenant on purpose. For an activity that is
 * the registration's own shape — a selector is an argument, and passing
 * nothing is a choice somebody had to make.
 *
 * <p>What has nothing enforcing it is the other half, which is where the
 * defect came from: a door mounted with an {@code if} at the site that creates
 * it, deciding for itself from the spec's shape. Every surface that has since
 * been converted was exactly that, and the one that started this — dispatching
 * for a tenant with no records on its face — was the same mistake with the
 * condition left out altogether. Nothing stops the next one being written the
 * same way, and it would not fail; it would work for the tenants its author
 * had in mind.
 *
 * <p>So the doors still mounted inline are counted here, by name, with why
 * each is still inline. A new one fails this test, which is the point: not
 * because mounting inline is forbidden, but because it should be a decision
 * somebody wrote down rather than the path of least resistance. Converting one
 * shrinks the list, so the list is also the remaining work, and it cannot
 * quietly grow.
 */
class ASurfaceSaysWhereItAppliesRatherThanWorkingItOutTest {

    private static final Path MANAGER = Path.of(
            "src/main/java/cloud/jengu/dbo/tenant/TenantRuntimeManager.java");

    /**
     * The per-tenant doors still mounted at the site that creates them, and
     * why each has not moved.
     *
     * <p>Each of these is a door a tenant either has or does not, so each has
     * a condition somewhere — which is the whole argument for it being said
     * out loud as a selector instead.
     */
    private static final Map<String, String> STILL_INLINE = new TreeMap<>(Map.of(
            "scimPath",
            "its block is not only a mount: it REFUSES the bring-up when scim is declared and "
                    + "unservable. An activity's failure is reported and the tenant still "
                    + "serves, so converting it as it stands would turn a tenant that refuses "
                    + "to come up misconfigured into one that serves without the door it "
                    + "declared. The declaration check moves to where declarations are "
                    + "checked first",
            "oidcPath",
            "the authority's own door, and the authority is what every other selector asks "
                    + "about — it exists before the facts a tenant publishes are resolved",
            "configurationPath", "conditional only on the enclosing authority block",
            "adminPath", "conditional only on the enclosing authority block",
            "workPath", "conditional only on the enclosing authority block",
            "fleetPath", "conditional only on the enclosing authority block",
            "replicationPath", "conditional only on the enclosing authority block"));

    @Test
    @DisplayName("every per-tenant door is either a registered activity or one this names, "
            + "with the reason it is still mounted where it is created")
    void noDoorQuietlyDecidesForItself() throws IOException {
        String src = Files.readString(manager());
        List<String> inline = new ArrayList<>();
        int at = 0;
        while ((at = src.indexOf("sharedServer.createContext(", at)) >= 0) {
            int open = src.indexOf('(', at + "sharedServer.createContext".length() - 1);
            String first = firstArgument(src, open);
            at += 1;
            if (!perTenant(src, first) || insideARegisteredActivity(src, at)) {
                continue;
            }
            inline.add(first);
        }

        assertTrue(inline.size() > 1,
                "this found " + inline.size() + " doors mounted inline, which is fewer than "
                        + "there are — so the scan has stopped reading the runtime rather than "
                        + "the runtime having stopped mounting them, and every assertion below "
                        + "would pass by finding nothing");

        assertEquals(new TreeSet<>(STILL_INLINE.keySet()), new TreeSet<>(inline),
                "a per-tenant door is mounted where it is created and is not one this test "
                        + "knows about. A door decides for itself which tenants have it, and "
                        + "the condition lives at the site rather than beside the thing it "
                        + "governs — which is how a tenant comes to be served a surface "
                        + "nobody meant it to have, or to be missing one nobody noticed. "
                        + "Register it at a lifecycle point with a selector over the facts a "
                        + "tenant publishes; if it genuinely cannot move yet, add it here with "
                        + "the reason, so the next reader inherits an argument rather than a "
                        + "habit");
    }

    /**
     * A door of a tenant's, rather than of the deployment or the zone.
     *
     * <p>Decided by what the path is built from, which is the only thing that
     * actually says so: a tenant's door has the tenant in it. {@code /runtime/…}
     * is the operator's, one per deployment, and the identity hub is a zone's
     * — mounted under {@code /z/}, and applying to a zone's tenants rather
     * than being one tenant's to have. Neither has a tenant to select on, so
     * neither is what this rule is about.
     */
    private static boolean perTenant(String src, String argument) {
        if (argument == null || argument.startsWith("\"")) {
            return false;
        }
        int declared = src.indexOf("String " + argument + " = ");
        if (declared < 0) {
            // A path this cannot trace back to where it was built is reported
            // rather than skipped: silently ignoring what it cannot read is
            // the failure this whole family of checks exists to avoid.
            return true;
        }
        int end = src.indexOf(';', declared);
        return src.substring(declared, end < 0 ? src.length() : end).contains("\"/t/\"");
    }

    /**
     * Whether this offset sits inside the arguments of an
     * {@code activities.register(…)} call — that is, inside an activity that
     * has already said where it applies.
     */
    private static boolean insideARegisteredActivity(String src, int offset) {
        int from = 0;
        while (true) {
            int register = src.indexOf("activities.register(", from);
            if (register < 0 || register > offset) {
                return false;
            }
            int end = endOfCall(src, src.indexOf('(', register + "activities.register".length() - 1));
            if (offset > register && offset < end) {
                return true;
            }
            from = register + 1;
        }
    }

    /** The index just past the bracket that closes the one opened at {@code open}. */
    private static int endOfCall(String src, int open) {
        int depth = 0;
        boolean inString = false;
        boolean inText = false;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inText) {
                inText = !src.startsWith("\"\"\"", i);
                continue;
            }
            if (src.startsWith("\"\"\"", i)) {
                inText = true;
                i += 2;
            } else if (inString) {
                inString = c != '"' || src.charAt(i - 1) == '\\';
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return src.length();
    }

    /** The first argument of the call whose bracket opens at {@code open}. */
    private static String firstArgument(String src, int open) {
        int depth = 0;
        boolean inString = false;
        StringBuilder argument = new StringBuilder();
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                inString = c != '"' || src.charAt(i - 1) == '\\';
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
                if (depth == 1) {
                    continue;
                }
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return argument.toString().trim();
                }
            } else if (c == ',' && depth == 1) {
                return argument.toString().trim();
            }
            if (depth >= 1) {
                argument.append(c);
            }
        }
        return null;
    }

    /** The tenant runtime's sources, wherever this happens to be run from. */
    private static Path manager() {
        Path fromModule = Path.of("../dbo-tenant").resolve(MANAGER);
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        Path fromRoot = Path.of("core/dbo-tenant").resolve(MANAGER);
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("the tenant runtime's sources are not where this "
                + "expected: " + fromModule.toAbsolutePath() + " nor " + fromRoot.toAbsolutePath());
    }
}
