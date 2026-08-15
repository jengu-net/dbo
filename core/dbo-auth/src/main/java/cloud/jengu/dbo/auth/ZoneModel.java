package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * The zone declarations (§17.1): regular records in the ZONE tenant's own
 * {@code zone} domain — versioned, audited, exported, streamable down the
 * chains. Secrets are NEVER in a record; the machinery resolves them from
 * custody by broker code.
 */
public final class ZoneModel {

    public static final String DOMAIN = "zone";
    public static final String BROKER_CODE_SYSTEM = "urn:dbo:zone:broker";
    public static final String IDENTIFIER_USE_SYSTEM = "urn:dbo:zone:identifier-use";

    /** The identifier-domain use for resolving human subjects. */
    public static final String USE_PERSON_PRIMARY = "person-primary";

    private ZoneModel() {
    }

    public static List<TypeRegistration> registrations() {
        EnvelopeExtractor broker = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.identifier(BROKER_CODE_SYSTEM, Json.str(n, "code"));
            e.value("status", EnvelopeValue.of(Json.str(n, "status")));
            return e;
        };
        EnvelopeExtractor identifierDomain = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.identifier(IDENTIFIER_USE_SYSTEM, Json.str(n, "use"));
            e.value("status", EnvelopeValue.of(Json.str(n, "status")));
            return e;
        };
        return List.of(
                new TypeRegistration("ZoneBroker", DOMAIN, IdentityClass.IDENTIFIER,
                        Set.of(BROKER_CODE_SYSTEM), broker, List.of()),
                new TypeRegistration("ZoneIdentifierDomain", DOMAIN, IdentityClass.IDENTIFIER,
                        Set.of(IDENTIFIER_USE_SYSTEM), identifierDomain, List.of()));
    }

    /** A declared broker; the secret arrives from custody, not the record. */
    public record Broker(String code, String issuer, String clientId,
            String subjectStripPrefix, String assuranceLevel) {

        public static Broker parse(byte[] payload) {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            return new Broker(Json.str(n, "code"), Json.str(n, "issuer"),
                    Json.str(n, "clientId"), Json.strOpt(n, "subjectStripPrefix"),
                    Json.strOpt(n, "assuranceLevel"));
        }

        public static byte[] payload(Broker broker) {
            return ("{\"code\":\"" + broker.code() + "\",\"issuer\":\"" + broker.issuer() + "\""
                    + ",\"clientId\":\"" + broker.clientId() + "\""
                    + (broker.subjectStripPrefix() != null
                            ? ",\"subjectStripPrefix\":\"" + broker.subjectStripPrefix() + "\"" : "")
                    + (broker.assuranceLevel() != null
                            ? ",\"assuranceLevel\":\"" + broker.assuranceLevel() + "\"" : "")
                    + ",\"status\":\"active\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    public static byte[] identifierDomainPayload(String use, String system) {
        return ("{\"use\":\"" + use + "\",\"system\":\"" + system + "\",\"status\":\"active\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
