package cloud.jengu.dbo.asking;

import java.util.ArrayList;
import java.util.List;

/**
 * Enough JSON to walk a search result, and no more.
 *
 * <p>Its own reader, like every other module here that touches a payload: a
 * short private one is cheaper than a bundle dependency shared for the sake of
 * not repeating one, and this module answers from a store without parsing
 * anything at all — the reader exists for the binding that talks over a
 * network.
 *
 * <p>What it needs to find is a bundle's members and its next link. It does
 * not need to understand a resource, because a caller asking through this
 * vocabulary receives the bytes and reads them with whatever it already uses.
 */
final class Bundles {

    private Bundles() {
    }

    /** The {@code resource} of each entry, as the bytes they arrived as. */
    static List<byte[]> members(String bundle) {
        List<byte[]> found = new ArrayList<>();
        int at = bundle.indexOf("\"entry\"");
        if (at < 0) {
            return found;
        }
        // Resumed AFTER the member just taken, not one character past where it
        // started. A member may itself be a bundle — a run renders as its own
        // collection — and scanning from inside one finds its entries again,
        // so every nested resource came back a second time as a member of the
        // outer answer. Two runs walked as four.
        int resource = bundle.indexOf("\"resource\"", at);
        while (resource >= 0) {
            int open = bundle.indexOf('{', resource);
            if (open < 0) {
                break;
            }
            int close = matching(bundle, open);
            if (close < 0) {
                break;
            }
            found.add(bundle.substring(open, close + 1)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            resource = bundle.indexOf("\"resource\"", close + 1);
        }
        return found;
    }

    /** The next page's link, or null where the store said there is none. */
    static String next(String bundle) {
        for (int relation = bundle.indexOf("\"relation\":\"next\""); relation >= 0;
                relation = bundle.indexOf("\"relation\":\"next\"", relation + 1)) {
            int url = bundle.indexOf("\"url\"", relation);
            if (url < 0) {
                return null;
            }
            int from = bundle.indexOf('"', bundle.indexOf(':', url)) + 1;
            return bundle.substring(from, bundle.indexOf('"', from));
        }
        return null;
    }

    /**
     * What a count answered.
     *
     * <p>Refuses when the answer carries no total, rather than handing back a
     * number meaning "there was no number". A tenant that refused the search,
     * or answered something other than a count bundle, is not an answer of
     * minus one — and a caller that put minus one on a screen would be shown a
     * wrong answer that looked right, which is the failure this store refuses
     * everywhere else.
     */
    static long total(String bundle) {
        int at = bundle.indexOf("\"total\"");
        if (at < 0) {
            throw new IllegalStateException("the tenant did not answer with a count: "
                    + bundle.substring(0, Math.min(400, bundle.length())));
        }
        int from = bundle.indexOf(':', at) + 1;
        int to = from;
        while (to < bundle.length() && (Character.isDigit(bundle.charAt(to))
                || Character.isWhitespace(bundle.charAt(to)))) {
            to++;
        }
        return Long.parseLong(bundle.substring(from, to).trim());
    }

    /** A top-level string field of a member, or "" where it carries none. */
    /**
     * The {@code value} or {@code code} standing beside a given system.
     *
     * <p>A run crosses as the face renders it — identifiers under their
     * systems, codings under theirs — and this reads one back out. Scanned
     * rather than parsed because what is being read is a shape this binding
     * already knows: it writes its questions in the same spelling, so a change
     * to either is a change to both, and a reader that understood arbitrary
     * JSON would not make that any safer.
     *
     * <p>The span searched is the object the system sits in, from the system
     * to the next closing brace, so a value belonging to a later identifier
     * cannot be read as this one's.
     *
     * @return the value, or empty where nothing carries that system
     */
    static String beside(String member, String system, String named) {
        int at = member.indexOf("\"system\":\"" + system + "\"");
        if (at < 0) {
            return "";
        }
        int ends = member.indexOf('}', at);
        String within = ends < 0 ? member.substring(at) : member.substring(at, ends);
        int said = within.indexOf("\"" + named + "\"");
        if (said < 0) {
            // The system may be written after the value in the same object.
            int opens = member.lastIndexOf('{', at);
            if (opens < 0) {
                return "";
            }
            within = member.substring(opens, ends < 0 ? member.length() : ends);
            said = within.indexOf("\"" + named + "\"");
            if (said < 0) {
                return "";
            }
        }
        int quote = within.indexOf('"', within.indexOf(':', said) + 1);
        int closing = within.indexOf('"', quote + 1);
        return quote < 0 || closing < 0 ? "" : within.substring(quote + 1, closing);
    }

    static String field(String member, String name) {
        int at = member.indexOf("\"" + name + "\"");
        if (at < 0) {
            return "";
        }
        int from = member.indexOf('"', member.indexOf(':', at)) + 1;
        int to = member.indexOf('"', from);
        return from <= 0 || to < 0 ? "" : member.substring(from, to);
    }

    /**
     * Where the object opened at {@code open} closes.
     *
     * <p>Counting braces, and counting them outside strings — a payload
     * carrying a brace in a name would otherwise end the object early, and the
     * member after it would be silently lost rather than reported.
     */
    private static int matching(String text, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int at = open; at < text.length(); at++) {
            char c = text.charAt(at);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}' && --depth == 0) {
                return at;
            }
        }
        return -1;
    }
}
