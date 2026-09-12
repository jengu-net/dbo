package cloud.jengu.dbo.definitions;

import java.util.List;

/**
 * One way of asking after a resource, in the form the database runs.
 *
 * <p>A search parameter is an expression that picks values out of a document,
 * and a version publishes about thirty of them per type. Every one is
 * evaluated over an object tree on every write — which is what the index is
 * made of, and the only reason re-indexing has to ship each payload back to
 * the JVM to be looked at.
 *
 * <p>Compiled when the definition arrives, for the reason the elements and the
 * invariants are: the answer never changes, and the thing that will run it is
 * a database. What is kept is the path that selects the values, the predicate
 * that narrows which of them count, and the typed rule saying what to make of
 * what comes back — a token is not a string and a date is not either.
 *
 * <p>The expression it came from is kept beside the compiled form. Not for
 * running: for reading, when somebody asks why a search answers the way it
 * does and what the specification actually said.
 *
 * <p>What the values ARE is kept too, because two token parameters can look
 * identical in a document and mean different things: an {@code Identifier} is
 * a claim on a name somebody else may also make, and a {@code ContactPoint}
 * carrying the same two fields is not. Only the definition tells them apart,
 * and this is where that reading is held.
 *
 * @param code          what a caller types: {@code identifier}, {@code birthdate}
 * @param base          the resource type it asks about
 * @param kind          token, string, date, reference, number, uri, and the
 *                      two this store does not extract
 * @param expression    the parameter as written, for a person
 * @param paths         where the values are, as this store selects them
 * @param predicate     which of them count, or null when all of them do
 * @param endsAt        what the values ARE, as the definition names the type
 *                      — {@code Identifier}, {@code ContactPoint} — or null
 *                      where the definition does not say one thing
 * @param unenforceable why it cannot be run here, or null when it can
 */
public record DefinitionParameter(
        String code,
        String base,
        String kind,
        String expression,
        List<String> paths,
        String predicate,
        String endsAt,
        String unenforceable) {

    public DefinitionParameter {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    /**
     * Whether the database can select by this parameter at all.
     *
     * <p>A parameter with no path selects nothing, which is not the same as
     * selecting nothing FOUND — so one that did not compile is held saying so
     * rather than held looking like a search that matches no documents.
     */
    public boolean enforceable() {
        return unenforceable == null && !paths.isEmpty();
    }

    /**
     * The two kinds this store does not take apart, stated rather than
     * discovered.
     *
     * <p>Composite and quantity are not extracted today and this work does not
     * add them: it moves where the extraction happens, and a move that also
     * changed what is extracted would make any difference impossible to
     * attribute.
     */
    public static boolean extractable(String kind) {
        return kind != null && !"composite".equals(kind) && !"quantity".equals(kind);
    }
}
