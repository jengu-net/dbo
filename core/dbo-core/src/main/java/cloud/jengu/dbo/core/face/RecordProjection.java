package cloud.jengu.dbo.core.face;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Renders the engine's <b>own</b> records — a run, an audit entry — as
 * documents in the domain's vocabulary, and reads back what a domain posted at
 * one of those surfaces.
 *
 * <p>The engine stores a run; the domain reads a {@code Task}. Which resource
 * that is, and how a holder is spelled in it, is knowledge about a domain, and
 * an engine module that hand-builds it is a face living in the wrong module —
 * translation with none of the properties translation is supposed to have.
 *
 * <p><b>At read, never inside the write.</b> A face that rendered during the
 * write that records a run would be re-entrancy inside a transaction, and two
 * faces running in parallel over one store could disagree about who wrote what.
 * So this takes the record as data and gives a document back; it never reaches
 * for the store, which is also why children arrive as an argument rather than
 * being fetched.
 *
 * <p><b>Projected or not is not a flag.</b> A record naming domains renders
 * where the face claims one of them and nowhere else, so nobody has to remember
 * to set anything and nobody can set it wrongly. A record naming no domain —
 * an audit entry is about an interaction rather than about a domain — is every
 * face's to render.
 */
public interface RecordProjection {

    /** The engine type names this face projects — {@code Run}, {@code AuditEntry}. */
    Set<String> projects();

    /**
     * @return the document, or empty when this face claims none of the domains
     *         the record names. Empty is an answer — the caller says the record
     *         does not render here, rather than serving an empty document that
     *         reads like a record with nothing in it.
     */
    Optional<String> project(Record record);

    /**
     * The vocabularies this face publishes, as canonical resources a client
     * can fetch.
     *
     * <p>dbo owns concepts the domain has no word for — a run's holder, the
     * tally of what a step did — and minting a system for them is how the
     * domain is meant to be extended. What is not allowed is a client meeting
     * one and having nowhere to look it up: a consumer learns the domain's
     * API and nothing else, so dbo's own vocabulary is discovered the way any
     * implementation guide's is, by fetching the resource that defines it.
     *
     * <p>Documents rather than a model: which resource defines a vocabulary
     * is domain knowledge, and this contract has no business naming one.
     *
     * @return the canonical definitions, empty when this face publishes none
     */
    default List<String> vocabularies() {
        return List.of();
    }

    /**
     * The other direction: what the engine should record, read out of a
     * document somebody posted.
     *
     * <p>Still a translation and still not an act — the answer is what to
     * record, and recording it is the engine's. Faces that serve no posting
     * surface leave it as it is.
     *
     * @return empty when this face reads nothing of that type
     */
    default Optional<Posted> readPosted(String typeName, String document) {
        return Optional.empty();
    }

    /**
     * The reverse of rendering a run: what a posted document that the face
     * renders runs as means — which step, under what key, with which inputs
     * — without the engine learning what the document is called. Empty when
     * the face renders no runs, or the document is not one.
     */
    default Optional<PostedRun> readPostedRun(String document) {
        return Optional.empty();
    }

    /**
     * A run as somebody outside the container authored it.
     *
     * @param stepId the declared step, as {@code <module>.<process>.<step>}
     * @param scope  the run's own name under that step — the key is the step and this
     * @param inputs slot to reference, exactly as the run will name them
     */
    record PostedRun(String stepId, String scope, java.util.Map<String, String> inputs) {

        public PostedRun {
            inputs = inputs == null ? java.util.Map.of() : java.util.Map.copyOf(inputs);
        }
    }

    /**
     * One record to render, with what belongs to it.
     *
     * @param children the records that are part of this one — a run's items.
     *                 A subprocess or a continuation elsewhere is not a child
     *                 and never arrives here: it is a reference in the payload.
     * @param domains  the storage domains this record's work concerned, empty
     *                 when it concerned none in particular
     */
    record Record(String typeName, String id, long versionId, byte[] payload,
            List<Record> children, List<String> domains) {

        /** A record with nothing under it. */
        public Record(String typeName, String id, long versionId, byte[] payload,
                List<String> domains) {
            this(typeName, id, versionId, payload, List.of(), domains);
        }
    }

    /**
     * What a posted document says the engine should record, in the engine's own
     * words: never the document itself, because the machinery stamps who and
     * when and a posted claim about either is not evidence.
     */
    record Posted(String code, String targetType, String targetId, byte[] contributed,
            String dedupKey) {

        /** What a face reads when it contributes nothing but the facts. */
        public Posted(String code, String targetType, String targetId) {
            this(code, targetType, targetId, null, null);
        }

        /** What a face reads from a document that carries no stable id. */
        public Posted(String code, String targetType, String targetId, byte[] contributed) {
            this(code, targetType, targetId, contributed, null);
        }

        /**
         * The stable id the poster gave this record, or null if it gave none
         *.
         *
         * <p>An appliance forwards its audit at-least-once, and the receiving
         * side makes that effectively-once by writing each event under the id
         * the appliance generated. Which element of a posted document carries
         * that id is the face's knowledge and nobody else's — R4 gives
         * {@code AuditEvent} no {@code identifier} element, so the id travels
         * in {@code meta.tag}, and an engine that knew that would have learned
         * a domain's shape.
         *
         * <p>What the engine does with it is the same thing it does with any
         * exclusive claim: write it once, and on the second delivery hand back
         * what is already there. No general search is involved, so the rule
         * that a conditional write names an identity stands untouched.
         */
        public String dedupKey() {
            return dedupKey;
        }

        /**
         * The domain's own words, serialised by the face — <b>opaque to the
         * engine</b>, which stores them and hands them back at render time
         * without ever looking inside.
         *
         * <p>Base64 where it is stored, and that is deliberate rather than
         * fussy: a JSON document nested as a string invites the engine to
         * parse it one day, and an engine that learned a domain's shape is the
         * mistake this contract exists to prevent. What the engine keeps
         * queryable is its OWN record — actor, interaction, target, time —
         * and nothing lifted out of a document a domain posted.
         */
        public byte[] contributed() {
            return contributed;
        }
    }
}
