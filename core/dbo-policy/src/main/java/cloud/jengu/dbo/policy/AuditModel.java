package cloud.jengu.dbo.policy;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
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
            return e;
        };
        return List.of(new TypeRegistration("AuditEntry", DOMAIN, IdentityClass.INTERNAL,
                Set.of(), extractor, List.of()));
    }

    public static byte[] entry(String actor, String interaction, String targetType,
            String targetId, String outcome, String rule) {
        return ("{\"actor\":\"" + actor + "\",\"interaction\":\"" + interaction + "\""
                + ",\"targetType\":\"" + targetType + "\""
                + (targetId != null ? ",\"targetId\":\"" + targetId + "\"" : "")
                + ",\"outcome\":\"" + outcome + "\""
                + (rule != null ? ",\"rule\":\"" + rule + "\"" : "")
                + ",\"at\":\"" + java.time.Instant.now() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
