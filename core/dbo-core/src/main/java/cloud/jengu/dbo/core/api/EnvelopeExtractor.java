package cloud.jengu.dbo.core.api;

import java.util.Optional;

/**
 * The personality hook: derives the searchable envelope from an opaque
 * payload. The engine never interprets the payload itself
 * (REQ-DBO-CORE-PAYLOAD-IS-TRUTH).
 */
@FunctionalInterface
public interface EnvelopeExtractor {

    Envelope extract(String typeName, byte[] payload);

    /**
     * The same extraction, named as something the database can run.
     *
     * <p>An extractor is a function of the bytes, and the bytes are already
     * in the database. Saying so lets the engine derive a record's index
     * where it lands rather than carrying every payload to the JVM and the
     * answer back — which is the whole cost of a reindex, and a cost a write
     * pays on top of storing what it was given.
     *
     * <p>Named rather than generated. What runs is a function the release
     * installed and a test holds to the same typed rules this extractor
     * keeps, record for record; a seam that built SQL here would be a second
     * extractor, and the question of whether the two agree would move from a
     * comparison to an argument.
     *
     * <p>Empty is the honest default. Most types are extracted by a few lines
     * of Java over a document nobody searches by thirty dimensions, and
     * carrying those to the database would be work with nothing to show.
     */
    default Optional<InTheStatement> inTheStatement() {
        return Optional.empty();
    }

    /**
     * What a write derives, as three functions of the document and its type.
     *
     * <p>Three because a write derives three things and they live in three
     * places: the envelope is a column, an identifier is a claim adjudicated
     * against everybody else's, and a reference is an edge a chained search
     * joins against. Which identifiers bear identity stays the engine's
     * question — it is asked of the type's registration, not of the bytes.
     *
     * @param envelope        {@code (jsonb, text) -> jsonb}
     * @param identifiers     {@code (jsonb, text) -> setof (system, value)}
     * @param referenceEdges  {@code (jsonb, text) -> setof (ref_type, target_type, target_id)}
     */
    record InTheStatement(String envelope, String identifiers, String referenceEdges) {

        private static final java.util.regex.Pattern NAMED =
                java.util.regex.Pattern.compile("[a-z_][a-z0-9_]{0,62}\\.[a-z_][a-z0-9_]{0,62}");

        /**
         * Schema-qualified, and nothing else. These names reach a statement
         * unparameterised, because a function name cannot be a parameter —
         * so what may be said here is spelt out rather than escaped.
         */
        public InTheStatement {
            for (String name : new String[] {envelope, identifiers, referenceEdges}) {
                if (name == null || !NAMED.matcher(name).matches()) {
                    throw new IllegalArgumentException(
                            "not a schema-qualified function name: " + name);
                }
            }
        }
    }
}
