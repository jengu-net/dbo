package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
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
    public static final String BINDING_SUBJECT_SYSTEM = "urn:dbo:identity:bound-subject";
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
        // Every claim that was presented becomes a (non-identity) identifier on
        // the decision, so the next resolution can ask "has anybody already
        // judged this claim?" without scanning. The claims are not identity
        // claims here — they identify a person, not this record — so they do
        // not collide with the person's own.
        EnvelopeExtractor adjudication = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.value("outcome", EnvelopeValue.of(Json.str(n, "outcome")));
            String subject = Json.strOpt(n, "subjectId");
            if (subject != null) {
                e.value("subjectId", EnvelopeValue.of(subject));
            }
            for (Object claim : Json.array(n, "presented")) {
                e.identifier(Json.str(claim, "system"), Json.str(claim, "value"));
            }
            return e;
        };
        // Bindings are events: the subject they concern is indexed so the
        // current attachments can be folded, and nothing is ever edited.
        EnvelopeExtractor binding = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.value("kind", EnvelopeValue.of(Json.str(n, "kind")));
            e.value("subjectId", EnvelopeValue.of(Json.str(n, "subjectId")));
            e.value("identityId", EnvelopeValue.of(Json.str(n, "identityId")));
            e.identifier(BINDING_SUBJECT_SYSTEM, Json.str(n, "subjectId"));
            return e;
        };
        EnvelopeExtractor anonymity = (type, payload) -> {
            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope e = new Envelope();
            e.value("kind", EnvelopeValue.of(Json.str(n, "kind")));
            e.value("subjectId", EnvelopeValue.of(Json.str(n, "subjectId")));
            e.identifier(BINDING_SUBJECT_SYSTEM, Json.str(n, "subjectId"));
            return e;
        };
        return List.of(
                // Append-only: a lifted declaration must not erase that it once
                // stood, or nobody can answer whether a person was identified
                // during a period they had asked not to be.
                new TypeRegistration("AnonymityEvent", DOMAIN, IdentityClass.INTERNAL,
                        java.util.Set.of(),
                        new Handling(Handling.Authority.TENANT_USERS,
                                Handling.Mutability.APPEND_ONLY,
                                Handling.Durability.VERSIONED,
                                Handling.Travel.BACKUP_ONLY),
                        anonymity, List.of()),
                // Append-only for the same reason as a decision: a withdrawal
                // that erased the binding would erase the evidence that anybody
                // was ever identified — exactly what somebody would want erased
                // if the binding had been wrong.
                new TypeRegistration("BindingEvent", DOMAIN, IdentityClass.INTERNAL,
                        java.util.Set.of(),
                        new Handling(Handling.Authority.TENANT_USERS,
                                Handling.Mutability.APPEND_ONLY,
                                Handling.Durability.VERSIONED,
                                Handling.Travel.BACKUP_ONLY),
                        binding, List.of()),
                // TENANT_USERS and APPEND_ONLY: a receptionist decides, and a
                // decision is never edited. Revising an identification means
                // recording a NEW decision that supersedes it — editing the old
                // one would destroy the evidence of what somebody concluded and
                // when, which is the reason to keep it at all. No named class
                // covers this combination, which is what the four properties are
                // for.
                new TypeRegistration("Adjudication", DOMAIN, IdentityClass.INTERNAL,
                        java.util.Set.of(),
                        new Handling(Handling.Authority.TENANT_USERS,
                                Handling.Mutability.APPEND_ONLY,
                                Handling.Durability.VERSIONED,
                                Handling.Travel.BACKUP_ONLY),
                        adjudication, List.of()),
                new TypeRegistration("ClientApplication", DOMAIN, IdentityClass.IDENTIFIER,
                        java.util.Set.of(CLIENT_ID_SYSTEM), Handling.storeAuthored(), client, List.of()),
                new TypeRegistration("SigningKey", DOMAIN, IdentityClass.IDENTIFIER,
                        java.util.Set.of(KID_SYSTEM), Handling.storeAuthored(), key, List.of()),
                new TypeRegistration("RoleGrant", DOMAIN, IdentityClass.IDENTIFIER,
                        java.util.Set.of(ROLE_CODE_SYSTEM), Handling.storeAuthored(), roleGrant, List.of()),
                new TypeRegistration("LocalCredential", DOMAIN, IdentityClass.IDENTIFIER,
                        java.util.Set.of(LOGIN_SYSTEM), Handling.storeAuthored(), credential, List.of()),
                new TypeRegistration("Delegation", DOMAIN, IdentityClass.INTERNAL,
                        java.util.Set.of(), Handling.storeAuthored(), (type, payload) -> {
                            Object n = Json.parse(new String(payload, StandardCharsets.UTF_8));
                            Envelope e = new Envelope();
                            e.value("status", EnvelopeValue.of(Json.str(n, "status")));
                            e.value("clientId", EnvelopeValue.of(Json.str(n, "clientId")));
                            return e;
                        }, List.of()));
    }
}
