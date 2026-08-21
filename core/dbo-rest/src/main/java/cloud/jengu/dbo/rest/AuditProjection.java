package cloud.jengu.dbo.rest;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.RecordProjection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The audit trail's read and write surface (§15.1): the native audit records
 * are the truth form, and what a client sees is a projection of them.
 *
 * <p>What lives here is the surface: which records answer a query, and what a
 * posted document causes to be recorded. <b>How an audit record is spelled is
 * not:</b> that is a translation, and it is the face's
 * ({@link RecordProjection}) — this builds no document and never learns the
 * shape of the one it serves.
 *
 * <p>It sits beside the HTTP surface rather than in the policy layer, because
 * a projection is a view and the policy layer is where the records are made.
 */
public final class AuditProjection implements AuditSurface {

    private static final String ENTRY = "AuditEntry";

    private final ObjectStore store;
    private final Recorder recorder;
    private final DomainFace face;

    /**
     * @param recorder what writes an entry, since the trail refuses direct
     *                 writes — the machinery stamps who and when, so recording
     *                 is the policy layer's and never this surface's
     */
    public AuditProjection(ObjectStore store, Recorder recorder, DomainFace face) {
        this.store = store;
        this.recorder = recorder;
        this.face = face;
    }

    /** Whatever may write an audit entry. */
    public interface Recorder {
        String recordCustom(String code, String targetType, String targetId,
                java.util.Map<String, String> detail);

        /**
         * The same, carrying what the face read out of a posted document —
         * bytes the recorder stores and never reads.
         */
        default String recordCustom(String code, String targetType, String targetId,
                java.util.Map<String, String> detail, byte[] contributed) {
            return recordCustom(code, targetType, targetId, detail);
        }
    }

    @Override
    public String search(Map<String, String> query, String baseUrl) {
        Criteria criteria = Criteria.of("AuditEntry").sortByLastUpdated(false).limit(100);
        if (query.get("agent") != null) {
            criteria.eq("actor", EnvelopeValue.of(query.get("agent")));
        }
        if (query.get("entity") != null) {
            criteria.eq("targetId", EnvelopeValue.of(query.get("entity")));
        }
        if (query.get("action") != null) {
            criteria.eq("interaction", EnvelopeValue.of(switch (query.get("action")) {
                case "C" -> "create";
                case "U" -> "update";
                case "D" -> "delete";
                case "R" -> "read";
                case "E" -> "custom";
                default -> query.get("action");
            }));
        }
        if (query.get("date") != null) {
            String date = query.get("date");
            if (date.startsWith("ge")) {
                criteria.lastUpdated(Criteria.RangeOp.GE, Instant.parse(normalized(date.substring(2))));
            } else if (date.startsWith("le")) {
                criteria.lastUpdated(Criteria.RangeOp.LE, Instant.parse(normalized(date.substring(2))));
            }
        }
        List<StoredObject> entries = store.select(criteria);
        PayloadFraming framing = face.require(PayloadFraming.class);
        PayloadFraming.Frame frame = framing.frame("searchset",
                new PayloadFraming.Facts((long) entries.size(), baseUrl + "/AuditEvent", null));
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        try {
            out.write(frame.prologue());
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    out.write(frame.separator());
                }
                StoredObject entry = entries.get(i);
                framing.member(new PayloadFraming.Member(ENTRY, entry.id(), entry.versionId(),
                        rendered(entry).getBytes(StandardCharsets.UTF_8),
                        baseUrl + "/AuditEvent/" + entry.id(), PayloadFraming.Member.MATCHED), out);
            }
            out.write(frame.epilogue());
        } catch (IOException e) {
            throw new UncheckedIOException("audit page could not be written", e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String normalized(String date) {
        return date.length() == 10 ? date + "T00:00:00Z" : date;
    }

    @Override
    public Optional<String> read(String id) {
        return store.get(ENTRY, id).map(this::rendered);
    }

    @Override
    public String create(String auditEventJson) {
        // What was posted decides the code and the target; who and when are the
        // machinery's, so a posted claim about either is not read at all (§15.1).
        RecordProjection.Posted posted = face.require(RecordProjection.class)
                .readPosted(ENTRY, auditEventJson)
                .orElseThrow(() -> new IllegalArgumentException(
                        "face '" + face.name() + "' reads no posted audit records, so this "
                                + "document cannot be turned into one"));
        // The detail map stays empty here on purpose: it is where dbo's OWN
        // writers put dbo's own coded facts, and lifting pieces out of a
        // posted document into it would be the engine learning a domain's
        // shape. What the domain said travels whole, in the face's words,
        // opaque to everything between here and the face that renders it.
        String id = recorder.recordCustom(posted.code(), posted.targetType(), posted.targetId(),
                Map.of(), posted.contributed());
        return read(id).orElseThrow();
    }

    /**
     * One entry, in the domain's words. An audit entry names no domain — it is
     * about an interaction rather than about a domain's records — so every face
     * renders it, and a face that declared no projection is a misconfiguration
     * the refusal names.
     */
    private String rendered(StoredObject entry) {
        return face.require(RecordProjection.class)
                .project(new RecordProjection.Record(ENTRY, entry.id(), entry.versionId(),
                        entry.payload(), List.of()))
                .orElseThrow(() -> new IllegalStateException(
                        "face '" + face.name() + "' renders no " + ENTRY));
    }
}
