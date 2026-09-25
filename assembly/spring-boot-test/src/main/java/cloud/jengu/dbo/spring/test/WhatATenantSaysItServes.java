package cloud.jengu.dbo.spring.test;

import cloud.jengu.dbo.fhir.validate.Documents;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A tenant's capability statement, as questions rather than as an object.
 *
 * <p><b>Text, and a reader over it.</b> The store composes this statement into
 * a StringBuilder and never holds it as a model, because a FHIR object graph
 * needs a populated worker context to parse into — which is the several
 * hundred megabytes item 025 exists to stop building, and which a serving node
 * now cannot build at all. A test helper that handed out model objects would
 * put that back in the one place nobody would look for it.
 *
 * <p>So this reads the document with the same dependency-free scanner the
 * store's own checks use, and answers what a test asks: does this tenant serve
 * that type, what may be done with it, what may it be searched by. A test
 * wanting the typed resource can add the toolchain itself and parse
 * {@link #text()} — explicitly, where the cost is visible.
 *
 * <p><b>It carries the document so a failure can show it.</b> An assertion
 * over parsed data says <i>expected true but was false</i> and leaves somebody
 * guessing; every question here has a {@code why} that names what was asked
 * and prints what answered.
 */
public final class WhatATenantSaysItServes {

    private final String tenant;
    private final String text;
    private final Object document;

    WhatATenantSaysItServes(String tenant, String text) {
        this.tenant = tenant;
        this.text = text;
        this.document = Documents.read(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The statement as it was served. */
    public String text() {
        return text;
    }

    /** Whether the tenant announces this type at all. */
    public boolean serves(String typeName) {
        return types().contains(typeName);
    }

    /** Every type the tenant announces, in the order it announces them. */
    public List<String> types() {
        return textsAt("$.\"rest\".\"resource\".\"type\"");
    }

    /** What the tenant says may be done with a type. */
    public List<String> interactionsWith(String typeName) {
        List<String> out = new ArrayList<>();
        for (Object resource : selected(document, "$.\"rest\".\"resource\"")) {
            if (typeName.equals(one(resource, "type"))) {
                for (Object interaction : selected(resource, "@.\"interaction\"")) {
                    String code = one(interaction, "code");
                    if (code != null) {
                        out.add(code);
                    }
                }
            }
        }
        return out;
    }

    /** What the tenant says a type may be searched by. */
    public List<String> searchableBy(String typeName) {
        List<String> out = new ArrayList<>();
        for (Object resource : selected(document, "$.\"rest\".\"resource\"")) {
            if (typeName.equals(one(resource, "type"))) {
                for (Object parameter : selected(resource, "@.\"searchParam\"")) {
                    String name = one(parameter, "name");
                    if (name != null) {
                        out.add(name);
                    }
                }
            }
        }
        return out;
    }

    /**
     * What to say when one of these answers is not what a test expected.
     *
     * <p><b>Short on purpose.</b> These are asked in groups — a test asserting
     * one thing about a statement usually asserts several, and a bring-up
     * costs minutes, so failing them one at a time is expensive. Grouped with
     * {@code assertAll}, every failure is reported at once, and a message
     * carrying the whole statement would print it once per failure. The
     * statement is one call away at {@link #text()} when it is wanted.
     */
    public String why(String asked) {
        return "asked whether " + tenant + " " + asked + ", and what it says it serves is "
                + types();
    }

    private List<String> textsAt(String path) {
        List<String> out = new ArrayList<>();
        for (Object value : selected(document, path)) {
            String said = Documents.text(value);
            if (said != null) {
                out.add(said);
            }
        }
        return out;
    }

    private static String one(Object instance, String field) {
        List<Object> found = selected(instance, "@.\"" + field + "\"");
        return found.isEmpty() ? null : Documents.text(found.get(0));
    }

    /**
     * What a path selects, refusing rather than returning nothing.
     *
     * <p><b>Null is a real answer from this reader</b>, and it means the path
     * could not be run rather than that it selected nothing. Treating the two
     * as one is the mistake the reader's own documentation warns about, and
     * here it would turn a malformed path into a tenant that quietly serves
     * no types — an assertion failing for a reason nowhere near the truth.
     */
    private static List<Object> selected(Object instance, String path) {
        List<Object> found = Documents.at(instance, path);
        if (found == null) {
            throw new IllegalArgumentException("this reader cannot run the path '" + path
                    + "', which is not the same as it selecting nothing");
        }
        return found;
    }
}
