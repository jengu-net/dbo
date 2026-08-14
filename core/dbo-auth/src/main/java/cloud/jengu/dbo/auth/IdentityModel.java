package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The identity sibling model (§13.2): client applications and signing keys
 * are REGULAR RECORDS in the tenant's own store — versioned, feed-visible,
 * exported with the tenant. A dbo-native model, not a FHIR profile bolt-on;
 * it rides the same engine as every other model.
 */
public final class IdentityModel {

    public static final String DOMAIN = "identity";
    public static final String CLIENT_ID_SYSTEM = "urn:dbo:auth:client-id";
    public static final String KID_SYSTEM = "urn:dbo:auth:kid";
    public static final String ROLE_CODE_SYSTEM = "urn:dbo:auth:role-code";
    public static final String LOGIN_SYSTEM = "urn:dbo:auth:login";

    private IdentityModel() {
    }

    public static List<TypeRegistration> registrations() {
        EnvelopeExtractor client = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.identifier(CLIENT_ID_SYSTEM, Json.str(n, "clientId"));
            e.value("status", EnvelopeValue.of(Json.str(n, "status")));
            return e;
        };
        EnvelopeExtractor key = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.identifier(KID_SYSTEM, Json.str(n, "kid"));
            e.value("status", EnvelopeValue.of(Json.str(n, "status")));
            return e;
        };
        EnvelopeExtractor roleGrant = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.identifier(ROLE_CODE_SYSTEM, Json.str(n, "roleCode"));
            e.value("status", EnvelopeValue.of(Json.str(n, "status")));
            return e;
        };
        EnvelopeExtractor credential = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.identifier(LOGIN_SYSTEM, Json.str(n, "login"));
            e.value("status", EnvelopeValue.of(Json.str(n, "status")));
            return e;
        };
        return List.of(
                new TypeRegistration("ClientApplication", DOMAIN, IdentityClass.IDENTIFIER,
                        java.util.Set.of(CLIENT_ID_SYSTEM), client, List.of()),
                new TypeRegistration("SigningKey", DOMAIN, IdentityClass.IDENTIFIER,
                        java.util.Set.of(KID_SYSTEM), key, List.of()),
                new TypeRegistration("RoleGrant", DOMAIN, IdentityClass.IDENTIFIER,
                        java.util.Set.of(ROLE_CODE_SYSTEM), roleGrant, List.of()),
                new TypeRegistration("LocalCredential", DOMAIN, IdentityClass.IDENTIFIER,
                        java.util.Set.of(LOGIN_SYSTEM), credential, List.of()),
                new TypeRegistration("Delegation", DOMAIN, IdentityClass.INTERNAL,
                        java.util.Set.of(), (type, payload) -> {
                            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
                            Envelope e = new Envelope();
                            e.value("status", EnvelopeValue.of(Json.str(n, "status")));
                            e.value("clientId", EnvelopeValue.of(Json.str(n, "clientId")));
                            return e;
                        }, List.of()));
    }
}
