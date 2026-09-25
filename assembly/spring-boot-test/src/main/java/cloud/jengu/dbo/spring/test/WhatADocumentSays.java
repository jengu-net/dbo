package cloud.jengu.dbo.spring.test;

import cloud.jengu.dbo.fhir.validate.Documents;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A document a test can ask questions of.
 *
 * <p>The store's own reader, over a response body. No model and no parser of
 * this module's own: a typed FHIR resource needs a populated worker context to
 * parse into, which is the weight item 025 spent itself removing, and a second
 * JSON library would be a second opinion about what a document says.
 *
 * <p><b>Paths are written the short way and compiled to the long one.</b> The
 * reader runs the jsonpath Postgres is handed, where a member is quoted —
 * {@code $."name"."family"}. That is right for the store, which compares its
 * answer against the database's, and tiresome for a test. So {@code
 * name.family} is accepted and quoted here, and anything already starting with
 * {@code $} or {@code @} is passed through untouched for the paths that need
 * more than member access.
 *
 * <p><b>A path this reader cannot run is refused, not answered empty.</b> Null
 * from the reader means "cannot run", which is categorically different from
 * "selected nothing" — and conflating them turns a mistyped path into a
 * document that appears to say nothing, which is an assertion failing for a
 * reason nowhere near the truth.
 */
public final class WhatADocumentSays {

    private final String text;
    private final Object document;

    WhatADocumentSays(String text) {
        this.text = text;
        this.document = Documents.read(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The body as it arrived. */
    public String text() {
        return text;
    }

    /** What a path selects, as text. Empty where the document says nothing there. */
    public List<String> at(String path) {
        List<String> out = new ArrayList<>();
        for (Object value : selected(path)) {
            String said = Documents.text(value);
            if (said != null) {
                out.add(said);
            }
        }
        return out;
    }

    /**
     * The one value a path selects, or empty.
     *
     * <p>Refuses where a path selected several: a test asking for "the" family
     * name of a document carrying two has asked a question the document does
     * not answer, and returning the first would hide that.
     */
    public java.util.Optional<String> one(String path) {
        List<String> found = at(path);
        if (found.size() > 1) {
            throw new AssertionError(path + " selects " + found.size() + " values, not one: "
                    + found + ". Ask with at(...) where a document may repeat.");
        }
        return found.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(found.get(0));
    }

    /** Whether the document says anything at all there. */
    public boolean has(String path) {
        return !selected(path).isEmpty();
    }

    private List<Object> selected(String path) {
        String compiled = compiled(path);
        List<Object> found = Documents.at(document, compiled);
        if (found == null) {
            throw new IllegalArgumentException("this reader cannot run the path '" + compiled
                    + "', which is not the same as the document saying nothing there");
        }
        return found;
    }

    /** {@code name.family} as {@code $."name"."family"}. */
    private static String compiled(String path) {
        if (path.startsWith("$") || path.startsWith("@")) {
            return path;
        }
        StringBuilder out = new StringBuilder("$");
        for (String member : path.split("\\.")) {
            out.append(".\"").append(member).append('"');
        }
        return out.toString();
    }
}
