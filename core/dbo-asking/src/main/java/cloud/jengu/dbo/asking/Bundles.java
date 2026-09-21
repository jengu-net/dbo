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
        for (int resource = bundle.indexOf("\"resource\"", at); resource >= 0;
                resource = bundle.indexOf("\"resource\"", resource + 1)) {
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

    /** What a count answered, or -1 where it said nothing. */
    static long total(String bundle) {
        int at = bundle.indexOf("\"total\"");
        if (at < 0) {
            return -1;
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
