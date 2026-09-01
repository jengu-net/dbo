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

        /**
         * The same, effectively-once under the stable id the poster gave the
         * event.
         *
         * <p>A recorder that cannot dedupe appends, which is the honest
         * degradation: the entry lands, and the trail carries a duplicate
         * rather than losing a delivery.
         */
        default String recordCustom(String code, String targetType, String targetId,
                java.util.Map<String, String> detail, byte[] contributed, String forwarded) {
            return recordCustom(code, targetType, targetId, detail, contributed);
        }

        /**
         * What one recording produced.
         *
         * @param created false when an earlier delivery of the same event had
         *                already landed under this id
         */
        record Entry(String id, boolean created) {
        }

        /**
         * The same recording, saying which of the two happened.
         *
         * <p>Default: a recorder that cannot dedupe appends and reports a
         * creation, which is what it did. Honest degradation — the entry
         * lands, and the trail carries a duplicate rather than losing a
         * delivery.
         */
        default Entry recordForwarded(String code, String targetType, String targetId,
                java.util.Map<String, String> detail, byte[] contributed, String forwarded) {
            return new Entry(recordCustom(code, targetType, targetId, detail,
                    contributed, forwarded), true);
        }
    }

    @Override
    public String search(Map<String, String> query, String baseUrl) {
        // A parameter this surface cannot honour is REFUSED, never ignored.
        // Ignoring one answers 200 with the unfiltered trail — a wrong answer
        // wearing the shape of a right one, which a caller cannot detect and
        // therefore cannot correct (REQ-DBO-SRCH-HONEST-CAPABILITY).
        for (String parameter : query.keySet()) {
            if (!searchParameters().contains(parameter)
                    && !cloud.jengu.dbo.fhir.common.ResultParameters
                            .shapesTheResult(parameter)) {
                throw new cloud.jengu.dbo.fhir.common.UnknownSearchParameterException(
                        "AuditEvent", parameter);
            }
        }
        // Newest first and one page unless the caller says otherwise: what the
        // trail always did, now sayable.
        Criteria criteria = Criteria.of("AuditEntry")
                .sortByLastUpdated(ascending(query.get("_sort")))
                .limit(bounded(query.get("_count")));
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

    /**
     * How many, bounded.
     *
     * <p>A page the caller did not ask for is 100, as it always was. A bound
     * the caller DID ask for is honoured up to a ceiling, because an audit
     * trail is the one collection that grows without limit and "all of it" is
     * not a page. Anything unreadable as a count is refused rather than
     * quietly replaced by a number nobody chose.
     */
    private static int bounded(String count) {
        return cloud.jengu.dbo.fhir.common.ResultParameters.count(count, "AuditEvent", 100, 1000);
    }

    /**
     * Which way round.
     *
     * <p>Only {@code date} orders the trail, and it is the only ordering the
     * records carry: an audit entry is an interaction at a time. Descending is
     * the default and the useful one — the last N events — and an ordering
     * the surface cannot give is refused rather than silently replaced by the
     * one it can, which would answer a different question than the one asked.
     */
    private static boolean ascending(String sort) {
        cloud.jengu.dbo.fhir.common.ResultParameters.Sort asked =
                cloud.jengu.dbo.fhir.common.ResultParameters.sort(sort, "AuditEvent");
        if (asked == null) {
            return false; // newest first: what the trail always did
        }
        if (!"date".equals(asked.field()) && !"_lastUpdated".equals(asked.field())) {
            // An audit entry is an interaction at a time, so time is the only
            // ordering the records carry. Refused rather than silently
            // answered with the ordering the surface can give, which would
            // answer a different question than the one asked.
            throw new cloud.jengu.dbo.fhir.common.UnknownSearchParameterException(
                    "AuditEvent", "_sort=" + sort);
        }
        return !asked.descending();
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
        return record(auditEventJson).rendered();
    }

    @Override
    public Recorded record(String auditEventJson) {
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
        // The dedup key rides with the rest: WHICH event this is, in the face's
        // reading of the document. An appliance forwards at-least-once, and
        // this is where that becomes effectively-once.
        Recorder.Entry entry = recorder.recordForwarded(posted.code(), posted.targetType(),
                posted.targetId(), Map.of(), posted.contributed(), posted.dedupKey());
        return new Recorded(read(entry.id()).orElseThrow(), entry.created());
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
