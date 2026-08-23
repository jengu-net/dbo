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
            return e;
        };
        return List.of(new TypeRegistration("AuditEntry", DOMAIN, IdentityClass.INTERNAL,
                Set.of(), Handling.audit(), extractor, List.of()));
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
        // somebody asks a year later (#114).
        String purpose = cloud.jengu.dbo.core.api.Disclosure.purpose();
        if (purpose != null) {
            sb.append(",\"purpose\":\"").append(purpose).append("\"");
        }
        // What was matched on, as a fingerprint. Taken rather than read, so it
        // lands on the entry for the search that produced it and on no other
        // (#115).
        String matched = cloud.jengu.dbo.core.api.Disclosure.takeMatched();
        if (matched != null) {
            sb.append(",\"matched\":\"").append(matched).append("\"");
        }
        sb.append(",\"outcome\":\"").append(outcome).append("\"");
        if (rule != null) {
            sb.append(",\"rule\":\"").append(rule).append("\"");
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
