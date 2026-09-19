package cloud.jengu.dbo.harness;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pulling one value out of an answer, and saying so when it is not there.
 *
 * <p><b>Why this exists.</b> The suite pulled tokens, ids and query parameters
 * out of responses with {@code replaceAll(".*name=([^&]+).*", "$1")}, which
 * has a failure mode nobody writes on purpose: <b>a replacement that matches
 * nothing returns what it was given</b>. So a refused token request did not
 * fail — it became the token, as the whole error document, and travelled on as
 * a bearer credential. The test then failed somewhere else entirely, with the
 * cause already discarded.
 *
 * <p>That is not hypothetical. It cost a CI cycle: a zone test died three
 * frames deep as {@code NoSuchElementException: No value present}, which named
 * a line in the test and nothing about the store, and the run had to be
 * repeated to learn anything. A refusal, a tenant that was not serving yet and
 * a credential that was never a credential all arrive there looking identical.
 *
 * <p>On a match these answer exactly what the replacement answered, so nothing
 * about a passing run changes. What changes is the failing one: it says which
 * value was wanted, and shows what it was looking in.
 */
final class Extracted {

    private static final Pattern ACCESS_TOKEN =
            Pattern.compile("\"access_token\":\"([^\"]+)\"", Pattern.DOTALL);

    private Extracted() {
    }

    /** The access token in an authority's answer, or a failure naming the answer. */
    static String tokenIn(String body) {
        return one(ACCESS_TOKEN, body, "an access token");
    }

    /**
     * The last segment of a path or url — the id in a {@code Location}.
     *
     * <p>Rejects a value with no segment to take rather than handing back the
     * whole thing, which is what a path-shaped answer that is actually an
     * error document would otherwise do.
     */
    static String lastSegment(String location) {
        if (location == null || location.isBlank() || location.endsWith("/")
                || location.indexOf('/') < 0) {
            return fail("no id to take from the end of '" + location + "'");
        }
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /**
     * A named field of a JSON answer, read as JSON.
     *
     * <p><b>Parsed, not matched, and that is the whole of it.</b> The patterns
     * these replaced were greedy, so they took the LAST field of that name in
     * the document; a reluctant one takes the first. Neither is a rule — they
     * are two different accidents. A StructureDefinition carries an {@code id}
     * on every element it defines, so its own is the last one and first-match
     * reads somebody else's; most other answers have exactly one, so
     * last-match was right by luck. The suite found this by failing, after
     * being told in a comment that nothing depended on it.
     *
     * <p>So the field is the document's own — top level, where an answer's
     * identity lives — and a document without one says so rather than offering
     * whatever else was lying around under that name.
     */
    static String field(String json, String name) {
        if (json == null || json.isBlank()) {
            return fail("looked for '" + name + "' in nothing at all");
        }
        Object parsed;
        try {
            parsed = cloud.jengu.dbo.core.wire.RecordWire.read(json);
        } catch (RuntimeException notJson) {
            return fail("looked for '" + name + "' in something that is not JSON: "
                    + shortened(json));
        }
        if (!(parsed instanceof java.util.Map<?, ?> fields)) {
            return fail("looked for '" + name + "' in JSON that is not an object: "
                    + shortened(json));
        }
        Object value = fields.get(name);
        if (value == null) {
            return fail("no '" + name + "' of its own in: " + shortened(json));
        }
        return String.valueOf(value);
    }

    /**
     * The id of the one resource a search answered with.
     *
     * <p>A search bundle has no id of its own — the thing being asked for is
     * inside it — so this walks to the entry rather than looking for the name
     * anywhere in the text. What it replaced took the LAST {@code id} in the
     * document, which is the right one only while the bundle holds exactly one
     * entry, and silently the wrong one the day a search matches two.
     *
     * <p>So it says how many it found instead. A test that searched by a
     * unique identifier and got two back has learnt something worth stopping
     * for.
     */
    static String soleMatchId(String bundle) {
        Object parsed;
        try {
            parsed = cloud.jengu.dbo.core.wire.RecordWire.read(bundle);
        } catch (RuntimeException notJson) {
            return fail("not a bundle at all: " + shortened(bundle));
        }
        if (!(parsed instanceof java.util.Map<?, ?> document)
                || !(document.get("entry") instanceof java.util.List<?> entries)) {
            return fail("no entries in what should be a search bundle: " + shortened(bundle));
        }
        if (entries.size() != 1) {
            return fail("a search that should have matched one thing matched " + entries.size()
                    + ": " + shortened(bundle));
        }
        if (!(entries.get(0) instanceof java.util.Map<?, ?> entry)
                || !(entry.get("resource") instanceof java.util.Map<?, ?> resource)
                || resource.get("id") == null) {
            return fail("the one entry carries no resource with an id: " + shortened(bundle));
        }
        return String.valueOf(resource.get("id"));
    }

    /** One query parameter of a url, by name. */
    static String queryParam(String url, String name) {
        return one(Pattern.compile(Pattern.quote(name) + "=([^&]+)"), url,
                "a '" + name + "' parameter");
    }

    /** Whether a url carries a parameter at all — for the answers that are a choice. */
    static boolean hasQueryParam(String url, String name) {
        return url != null && Pattern.compile(Pattern.quote(name) + "=([^&]+)")
                .matcher(url).find();
    }

    /**
     * The first group of the first match, or a failure that shows where it
     * looked.
     *
     * <p>Trimmed to a few hundred characters: the thing being searched is
     * regularly a whole response body, and a failure message that is a
     * kilobyte of JSON is one nobody reads to the end.
     */
    static String one(Pattern pattern, String text, String what) {
        if (text == null) {
            return fail("looked for " + what + " in nothing at all");
        }
        Matcher found = pattern.matcher(text);
        if (!found.find()) {
            return fail("no " + what + " in: " + shortened(text));
        }
        return found.group(1);
    }

    private static String shortened(String text) {
        return text.length() <= 400 ? text : text.substring(0, 400) + "… (" + text.length()
                + " characters)";
    }
}
