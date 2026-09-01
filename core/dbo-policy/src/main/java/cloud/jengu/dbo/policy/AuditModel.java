package cloud.jengu.dbo.policy;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * The audit sibling model (§15.1): entries are REGULAR RECORDS in the
 * tenant's own store — feed-visible, exported with the tenant,
 * pseudonymous by §14 construction (actor + interaction + target ids,
 * never content). Domain {@code audit}, so the accountability stream has
 * its own outbox and its own export.
 */
public final class AuditModel {

    public static final String DOMAIN = "audit";

    /**
     * The system a forwarded entry's own id is claimed under.
     *
     * <p>An appliance forwards its audit at-least-once and the receiving side
     * makes that effectively-once, which needs the forwarder's id to be an
     * EXCLUSIVE claim in this tenant: the second delivery of one event must
     * find the first rather than land beside it. An entry this store made
     * itself carries no such id and claims nothing.
     */
    public static final String FORWARDED_SYSTEM = "urn:dbo:audit:forwarded";

    private AuditModel() {
    }

    public static List<TypeRegistration> registrations() {
        EnvelopeExtractor extractor = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.value("actor", EnvelopeValue.of(Json.str(n, "actor")));
            e.value("interaction", EnvelopeValue.of(Json.str(n, "interaction")));
            e.value("targetType", EnvelopeValue.of(Json.str(n, "targetType")));
            if (((java.util.Map<?, ?>) n).get("targetId") != null) {
                e.value("targetId", EnvelopeValue.of(Json.str(n, "targetId")));
            }
            if (((java.util.Map<?, ?>) n).get("run") != null) {
                e.value("run", EnvelopeValue.of(Json.str(n, "run")));
            }
            if (((java.util.Map<?, ?>) n).get("code") != null) {
                e.value("code", EnvelopeValue.of(Json.str(n, "code")));
            }
            if (((java.util.Map<?, ?>) n).get("forwarded") != null) {
                e.identifier(FORWARDED_SYSTEM, Json.str(n, "forwarded"));
            }
            // Where it happened. Only a replicated entry carries it — an
            // entry this store wrote happened here, and saying so on every
            // row would be a constant. Queryable because "who did this, and
            // on which bench" is one question, and an operator asking it of a
            // cloud holding four appliances' trails cannot answer it from the
            // actor alone.
            if (((java.util.Map<?, ?>) n).get("appliance") != null) {
                e.value("appliance", EnvelopeValue.of(Json.str(n, "appliance")));
            }
            return e;
        };
        // IDENTIFIER rather than INTERNAL, and only barely: the ONLY system
        // that identifies an entry is the forwarder's own id, and an entry
        // this store wrote emits none — so nothing dbo records claims
        // anything, and a forwarded one claims exactly once.
        return List.of(new TypeRegistration("AuditEntry", DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(FORWARDED_SYSTEM), Handling.audit(), extractor, List.of()));
    }

    /**
     * One appliance's entry, as it arrives at another (§7.8).
     *
     * <p>Provenance is added and nothing else is touched: what the source
     * recorded travels as the source's bytes, and the two fields put on it
     * here are the two facts the source could not know — which appliance it
     * turned out to be, from the receiver's point of view, and the claim that
     * makes a second delivery idempotent.
     *
     * <p>The same shape as a mirrored run, which is filed under the appliance
     * that authored it for the same reason: without the source on the record,
     * two appliances' accounts of the same tenant become one indistinguishable
     * pile, and "applied 46 here, 44 there" stops being a question anybody can
     * ask.
     */
    public static byte[] recordedElsewhere(byte[] payload, String appliance, String claim) {
        Object node = Json.parse(new String(payload, StandardCharsets.UTF_8));
        if (!(node instanceof java.util.Map<?, ?> fields)) {
            throw new IllegalArgumentException("an audit entry arrives as an object");
        }
        java.util.Map<String, Object> stamped = new java.util.LinkedHashMap<>();
        fields.forEach((key, value) -> stamped.put(String.valueOf(key), value));
        stamped.put("appliance", appliance);
        stamped.put("forwarded", claim);
        return Json.render(stamped).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] entry(String actor, String interaction, String targetType,
            String targetId, String outcome, String rule) {
        return entry(actor, interaction, targetType, targetId, outcome, rule, null, java.util.Map.of());
    }

    /**
     * §15.1 custom events: the caller contributes code + coded detail; the
     * ACTOR and TIME are always the machinery's — never parameters here by
     * accident: actor comes from the caller seam upstream.
     */
    public static byte[] entry(String actor, String interaction, String targetType,
            String targetId, String outcome, String rule, String code,
            java.util.Map<String, String> detail) {
        return entry(actor, interaction, targetType, targetId, outcome, rule, code, detail, null);
    }

    /**
     * The same, carrying what a domain contributed in its own words.
     *
     * <p>{@code contributed} is <b>base64 and stays base64</b>: the engine
     * carries it and never reads it. dbo holds a general envelope over its own
     * facts — actor, interaction, target, time — and a tenant's document is
     * not a thing it may learn the shape of.
     */
    public static byte[] entry(String actor, String interaction, String targetType,
            String targetId, String outcome, String rule, String code,
            java.util.Map<String, String> detail, byte[] contributed) {
        return entry(actor, interaction, targetType, targetId, outcome, rule, code,
                detail, contributed, null);
    }

    /**
     * The same, carrying the stable id a forwarder gave this event.
     *
     * <p>It is written into the record rather than kept beside it because the
     * envelope is recomputed from the payload: an id that lived only in the
     * claim would be gone the next time the record was read back.
     */
    public static byte[] entry(String actor, String interaction, String targetType,
            String targetId, String outcome, String rule, String code,
            java.util.Map<String, String> detail, byte[] contributed, String forwarded) {
        StringBuilder sb = new StringBuilder("{\"actor\":\"").append(actor)
                .append("\",\"interaction\":\"").append(interaction).append("\"")
                .append(",\"targetType\":\"").append(targetType).append("\"");
        if (targetId != null) {
            sb.append(",\"targetId\":\"").append(targetId).append("\"");
        }
        if (code != null) {
            sb.append(",\"code\":\"").append(code).append("\"");
        }
        String run = cloud.jengu.dbo.core.api.Caller.run();
        if (run != null) {
            // what this interaction was part of, so a run's own account and the
            // trail of what it did are one join (REQ-DBO-PROC-TRACE-JOIN)
            sb.append(",\"run\":\"").append(run).append("\"");
        }
        String onBehalfOf = cloud.jengu.dbo.core.api.Caller.onBehalfOf();
        if (onBehalfOf != null) {
            sb.append(",\"onBehalfOf\":\"").append(onBehalfOf).append("\"");
        }
        // What the caller said they needed an identity for. Recorded because
        // the purpose is the only part of a disclosure that outlives the
        // request: the read is gone, and "who saw this person, and why" is what
        // somebody asks a year later.
        String purpose = cloud.jengu.dbo.core.api.Disclosure.purpose();
        if (purpose != null) {
            sb.append(",\"purpose\":\"").append(purpose).append("\"");
        }
        // What was matched on, as a fingerprint. Taken rather than read, so it
        // lands on the entry for the search that produced it and on no other
        //.
        String matched = cloud.jengu.dbo.core.api.Disclosure.takeMatched();
        if (matched != null) {
            sb.append(",\"matched\":\"").append(matched).append("\"");
        }
        sb.append(",\"outcome\":\"").append(outcome).append("\"");
        if (rule != null) {
            sb.append(",\"rule\":\"").append(rule).append("\"");
        }
        if (forwarded != null && !forwarded.isBlank()) {
            sb.append(",\"forwarded\":\"").append(forwarded).append('"');
        }
        if (contributed != null && contributed.length > 0) {
            sb.append(",\"contributed\":\"")
                    .append(java.util.Base64.getEncoder().encodeToString(contributed))
                    .append('"');
        }
        if (!detail.isEmpty()) {
            sb.append(",\"detail\":{");
            boolean first = true;
            for (java.util.Map.Entry<String, String> e : detail.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
            }
            sb.append('}');
        }
        return sb.append(",\"at\":\"").append(java.time.Instant.now()).append("\"}")
                .toString().getBytes(StandardCharsets.UTF_8);
    }
}
