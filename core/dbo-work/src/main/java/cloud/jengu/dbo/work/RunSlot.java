package cloud.jengu.dbo.work;

import java.util.List;

/**
 * What one of a run's slots was filled with.
 *
 * <p>A slot used to be one string, and one string meant one reference. A step
 * declares four shapes now — one object or several, referred to or given with
 * the run — so the fill has to be able to say which it is.
 *
 * <p><b>A slot is homogeneous</b>, and that is what keeps this small. The
 * declaration says {@code Reference(Organization)[]} or {@code Organization[]}
 * and never both, so a slot is either all references or all given objects and
 * a value never has to carry its own kind. Which in turn makes the run's JSON
 * say it without a marker: a string is a reference, an object is one given
 * here, and an array is several of whichever.
 *
 * <pre>
 * "org":      "Organization/123"          one reference
 * "notes":    ["Basic/1", "Basic/2"]      several references
 * "proposed": {"resourceType": …}         one object, given
 * "drafts":   [{…}, {…}]                  several objects, given
 * </pre>
 *
 * <p>{@code many} is kept rather than read off the count, because a repeating
 * slot filled once is still a repeating slot: rendering it as a single value
 * would make the run say something the declaration does not, and a reader
 * comparing the two would find a difference that is not one.
 *
 * @param kind   whether the values are references or objects
 * @param values the references, or the objects as their JSON text, in the
 *               order they were given — order is part of what was asked for
 * @param many   whether the slot was declared repeating
 */
public record RunSlot(Kind kind, List<String> values, boolean many) {

    /** Where the object is. */
    public enum Kind {
        /** The tenant holds it; the value is a reference. */
        REFERRED,
        /** It arrived with the run; the value is the object itself. */
        GIVEN
    }

    public RunSlot {
        if (kind == null) {
            throw new IllegalArgumentException("a filled slot says whether it refers or carries");
        }
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("a slot filled with nothing is a slot unfilled, "
                    + "and every declared slot is mandatory");
        }
        if (!many && values.size() > 1) {
            throw new IllegalArgumentException("a slot that does not repeat was given "
                    + values.size() + " values");
        }
    }

    /** One reference, which is what almost every run fills a slot with. */
    public static RunSlot referring(String reference) {
        return new RunSlot(Kind.REFERRED, List.of(reference), false);
    }

    /** Several references. */
    public static RunSlot referringTo(List<String> references) {
        return new RunSlot(Kind.REFERRED, references, true);
    }

    /** One object, given with the run and held by nothing else. */
    public static RunSlot given(String json) {
        return new RunSlot(Kind.GIVEN, List.of(json), false);
    }

    /** Several such objects. */
    public static RunSlot givenAll(List<String> json) {
        return new RunSlot(Kind.GIVEN, json, true);
    }

    /** Whether the values are references. */
    public boolean referred() {
        return kind == Kind.REFERRED;
    }

    /**
     * A given value as the object a performer is handed.
     *
     * <p><b>No id and version zero</b>, because it is not a record and saying
     * otherwise would let a performer treat it as one — read it again, write a
     * new version of it, cite it in a trail. It says its own type: a FHIR
     * resource carries {@code resourceType}, so the lane needs no declaration
     * in hand to deliver it, and an object whose type disagrees with the slot
     * it was put in was refused at the door where the declaration was.
     */
    public static cloud.jengu.dbo.core.api.StoredObject asObject(String json) {
        String type = Json.str(Json.parse(json), "resourceType");
        return new cloud.jengu.dbo.core.api.StoredObject(null, type, 0L,
                java.time.Instant.now(),
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), false, null);
    }

    /**
     * What the wire calls a given value, in place of the reference a referred
     * one has.
     *
     * <p>A token rather than the object, because a manifest is what anybody
     * carrying the work may read and a given object's content is the one thing
     * that must not be in it. The content travels as a sealed payload under
     * this name.
     */
    public static String token(String slot, int at) {
        return "given:" + slot + "#" + at;
    }

    /** Every value as the wire names it: a reference, or a token. */
    public java.util.List<String> tokens(String slot) {
        java.util.List<String> named = new java.util.ArrayList<>();
        for (int at = 0; at < values.size(); at++) {
            named.add(referred() ? values.get(at) : token(slot, at));
        }
        return named;
    }

    /**
     * The one value, for a slot that does not repeat.
     *
     * <p>Refused rather than returning the first, because the first of several
     * is an answer that looks right everywhere it is wrong.
     */
    public String one() {
        if (values.size() != 1) {
            throw new IllegalStateException("this slot holds " + values.size()
                    + " values and was asked for one");
        }
        return values.get(0);
    }
}
