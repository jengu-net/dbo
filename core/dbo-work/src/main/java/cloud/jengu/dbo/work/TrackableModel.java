package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * A participant some router said sits behind it.
 *
 * <p>One row per routee, carrying the participant it sits behind: the edge a
 * seal past a router follows, and nothing about what the routee is or how it
 * is doing. That is the router's to say in its heartbeat, to whoever listens.
 *
 * <p><b>Trust is delegated down the chain.</b> The store has no path of its
 * own to a routee, so a router is trusted about what is behind it exactly as
 * it is trusted about itself: it is enrolled and authenticated, and a router
 * lying about its routees is the same problem as one lying about itself.
 *
 * <p>Domain {@code work}, beside the declarations.
 */
public final class TrackableModel {

    /** The type name a routee's edge is registered under. Not a FHIR type. */
    public static final String TYPE = "Trackable";

    /** A routee's name, as it enrolled: the name a seal is addressed to. */
    public static final String ID_SYSTEM = "urn:dbo:trackable";

    private TrackableModel() {
    }

    public static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, WorkModel.DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(ID_SYSTEM), Handling.storeAuthored(), extractor(), List.of()));
    }

    /** {@code routedBy} is what sealing asks: what sits directly behind this router. */
    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Object trackable = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(ID_SYSTEM, Json.str(trackable, "id"));
            envelope.value("id", EnvelopeValue.of(Json.str(trackable, "id")));
            if (((java.util.Map<?, ?>) trackable).get("routedBy") != null) {
                envelope.value("routedBy", EnvelopeValue.of(Json.str(trackable, "routedBy")));
            }
            return envelope;
        };
    }

    /** The identity a trackable is found by. */
    public static Identifier key(String id) {
        return new Identifier(ID_SYSTEM, id);
    }
}
