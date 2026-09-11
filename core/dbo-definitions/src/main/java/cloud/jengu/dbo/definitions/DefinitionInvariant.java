package cloud.jengu.dbo.definitions;

/**
 * One rule an element carries, in the form the database runs
 * (REQ-DBO-VAL-AN-INVARIANT-IS-COMPILED-WHEN-IT-ARRIVES).
 *
 * <p>A constraint is written as an expression in a language built for walking
 * object trees. Compiling it is the same move the elements themselves get:
 * done once, when the definition arrives, because the answer never changes and
 * what runs it is a database.
 *
 * <p>The expression it came from is kept beside the compiled path. Not for
 * running — for reading, when somebody asks why a document was refused and
 * what the specification actually said.
 *
 * @param elementId     the element the rule is about
 * @param key           what the specification calls it: {@code dom-2}
 * @param severity      error or warning, as the definition states it
 * @param expression    the rule as written, for a person
 * @param path          the rule as this store runs it, or null
 * @param unenforceable why it cannot be run here, or null when it can
 */
public record DefinitionInvariant(
        String elementId,
        String key,
        String severity,
        String expression,
        String path,
        String unenforceable) {

    /** Whether the database can act on this rule at all. */
    public boolean enforceable() {
        return path != null;
    }
}
