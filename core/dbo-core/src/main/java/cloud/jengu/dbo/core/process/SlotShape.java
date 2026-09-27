package cloud.jengu.dbo.core.process;

/**
 * What a slot takes, read from the one string a declaration writes.
 *
 * <p>A slot used to say only a type, and a type meant one reference to one
 * stored object. Two more things are wanted of it and a bare type can carry
 * neither: whether the object is <b>referred to</b> or <b>given</b>, and
 * whether there is one or several.
 *
 * <pre>
 * Organization                one object, given with the run
 * Reference(Organization)     one reference to an object the tenant holds
 * Organization[]              several objects, given with the run
 * Reference(Organization)[]   several references
 * </pre>
 *
 * <p><b>The notation is FHIR's.</b> {@code Reference(Organization)} is how a
 * StructureDefinition says this, so a slot reads the way the thing it is over
 * is already described, and nobody has to learn a second spelling for a
 * concept the specification already names. {@code []} is the ordinary array
 * suffix, which is the one part FHIR has no notation for because it says
 * cardinality on the element instead.
 *
 * <p><b>Referring is the thing this store adds</b>, and it is why the two are
 * not one. A reference names data the initiator need not hold, need not be
 * entitled to read and need not send: the store resolves it where the data
 * already is, and the performer is handed the object without either end
 * putting it on the wire. A given object is the opposite case and is there for
 * what has no record yet — a proposal, a draft, something that arrived with
 * the request and may never be stored at all.
 *
 * <p><b>A given object is not written to the store.</b> It travels with the
 * run, is opened by whoever performs it, and stops there. A step that decides
 * one should become a record writes it as its own act, on its own entitlement
 * — which keeps "this arrived" and "this was kept" two statements rather than
 * one silent one.
 */
public record SlotShape(String type, Source from, boolean many) {

    /** Where the object comes from. */
    public enum Source {
        /** The tenant already holds it; the slot carries a reference. */
        STORE,
        /** It arrives with the run and is held by nothing else. */
        GIVEN
    }

    private static final String MANY = "[]";
    private static final String REFERENCE = "Reference(";

    public SlotShape {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("a slot names the type it takes");
        }
        if (type.endsWith(MANY) || type.startsWith(REFERENCE)) {
            // The notation is the declaration's, not the type's. A type
            // carrying part of it would round-trip to a different shape than
            // it was read from, which nothing notices until a register says
            // something untrue.
            throw new IllegalArgumentException("'" + type + "' is not a type: 'Reference(…)' "
                    + "and '" + MANY + "' are how a slot is declared, not part of what it takes");
        }
    }

    /**
     * Reads the declared form.
     *
     * <p>{@code []} is outermost, as it is everywhere it is written: a slot of
     * several references is {@code Reference(X)[]}, and {@code Reference(X[])}
     * is not another way of saying it. A grammar with two spellings for one
     * shape is two declarations that agree without looking like it — and
     * comparing them, which is what change detection is, would report a
     * difference that is not one.
     */
    public static SlotShape of(String declared) {
        if (declared == null || declared.isBlank()) {
            throw new IllegalArgumentException("a slot declares what it takes");
        }
        String rest = declared.trim();
        boolean many = rest.endsWith(MANY);
        if (many) {
            rest = rest.substring(0, rest.length() - MANY.length()).trim();
        }
        if (!rest.startsWith(REFERENCE)) {
            return new SlotShape(rest, Source.GIVEN, many);
        }
        if (!rest.endsWith(")")) {
            throw new IllegalArgumentException("'" + declared + "' opens 'Reference(' and does "
                    + "not close it");
        }
        String target = rest.substring(REFERENCE.length(), rest.length() - 1).trim();
        if (target.endsWith(MANY)) {
            throw new IllegalArgumentException("'" + declared + "' says '" + MANY + "' inside "
                    + "the reference; several references are declared 'Reference("
                    + target.substring(0, target.length() - MANY.length()) + ")" + MANY + "'");
        }
        if (target.contains("|")) {
            // FHIR writes Reference(Patient | Group) and this does not read
            // it yet. Said by name rather than parsed into the first target,
            // which would silently accept a declaration and then refuse every
            // object of the types it listed second.
            throw new IllegalArgumentException("'" + declared + "' names more than one target "
                    + "type. A slot takes one type here: declare a slot per type, or one over "
                    + "the type they share");
        }
        return new SlotShape(target, Source.STORE, many);
    }

    /** The string a declaration writes, which {@link #of} reads back. */
    public String declared() {
        String one = from == Source.STORE ? REFERENCE + type + ")" : type;
        return many ? one + MANY : one;
    }

    /** Whether the slot is filled by a reference rather than by an object. */
    public boolean referred() {
        return from == Source.STORE;
    }

    @Override
    public String toString() {
        return declared();
    }
}
