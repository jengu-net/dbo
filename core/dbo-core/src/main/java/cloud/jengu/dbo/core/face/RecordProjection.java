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
    record Posted(String code, String targetType, String targetId, byte[] contributed) {

        /** What a face reads when it contributes nothing but the facts. */
        public Posted(String code, String targetType, String targetId) {
            this(code, targetType, targetId, null);
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
