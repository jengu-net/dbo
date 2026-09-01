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
 * Something whose state is worth knowing, at any depth.
 *
 * <p><b>The topology is a tree, and only its root has a cursor.</b> A
 * connected worker reports for itself; it may also be a <b>router</b>,
 * carrying the state of things behind it — an appliance behind a connector,
 * an instrument behind an appliance — to arbitrary depth. Every one of those
 * is the same kind of thing, so there is one record shape and one row per
 * trackable however far down it sits. State normalised in one place is the
 * whole point: three routers each inventing "what is the state of the thing
 * behind the thing" would disagree, and the disagreement would surface as an
 * operator question nobody can answer.
 *
 * <p><b>What a trackable IS stays outside this repository.</b> The engine
 * knows that a trackable may route other trackables and nothing else about
 * what any of them are — the same line the run record holds, where the engine
 * never says {@code Task} and a face renders one. A face may later project a
 * trackable and its state onto whatever resource its version spells that with;
 * that word does not belong here, and this model is written so it never has to.
 *
 * <p><b>Trust is delegated down the chain.</b> The store has no independent
 * path to a routed trackable — everything it can know arrived through the
 * router — so a router is trusted about its routees exactly as it is trusted
 * about itself. It is enrolled and authenticated, and a router lying about
 * what is behind it is the same problem as one lying about itself.
 *
 * <p><b>So the store imposes no freshness rule</b>, deliberately. It has no
 * means to evaluate one, and a single threshold would be wrong anyway: an
 * instrument on a serial line and an appliance on a socket have nothing
 * sensible in common to threshold on. Each hop owns liveness for the hop below
 * it, with whatever protocol suits that hop. Report quality is the router's
 * contract, and a router that reports badly is a fact about that router.
 *
 * <p>Domain {@code work}, beside the declarations it annotates.
 */
public final class TrackableModel {

    /** The type name a trackable is registered under. Not a FHIR type. */
    public static final String TYPE = "Trackable";

    /**
     * A trackable's own id, as the router that reports it names it.
     *
     * <p>Globally stable within the tenant and opaque here: what makes an
     * instrument identifiable is the router's business, and a store that
     * invented the scheme would be deciding what a trackable is.
     */
    public static final String ID_SYSTEM = "urn:dbo:trackable";

    private TrackableModel() {
    }

    public static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, WorkModel.DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(ID_SYSTEM), Handling.storeAuthored(), extractor(), List.of()));
    }

    /**
     * State about a thing, nothing about a subject.
     *
     * <p>{@code routedBy} is the queryable one that matters: "what is behind
     * this connector" and "who last saw this instrument" are the two questions
     * an operator asks, and both are answered by walking it.
     */
    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Object trackable = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(ID_SYSTEM, Json.str(trackable, "id"));
            envelope.value("id", EnvelopeValue.of(Json.str(trackable, "id")));
            envelope.value("kind", EnvelopeValue.of(Json.str(trackable, "kind")));
            if (((java.util.Map<?, ?>) trackable).get("routedBy") != null) {
                envelope.value("routedBy", EnvelopeValue.of(Json.str(trackable, "routedBy")));
            }
            if (((java.util.Map<?, ?>) trackable).get("observedBy") != null) {
                envelope.value("observedBy", EnvelopeValue.of(Json.str(trackable, "observedBy")));
            }
            return envelope;
        };
    }

    /** The identity a trackable is found by. */
    public static Identifier key(String id) {
        return new Identifier(ID_SYSTEM, id);
    }
}
