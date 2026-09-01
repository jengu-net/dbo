package cloud.jengu.dbo.sync;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.work.WorkModel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * A lane between two appliances of one tenant, as a record (#80).
 *
 * <p>What it holds is the pair of facts neither side can hold alone: the
 * <b>epoch</b> this lane is running under, and the <b>marker</b> the far side
 * last said it had reached. A record rather than a table because both are
 * things an operator asks about, and because a lane surviving a backup is how
 * a restored appliance is caught: its epoch will not match the live one.
 */
public final class LaneModel {

    /** The type name a lane is registered under. */
    public static final String TYPE = "Lane";

    /** A lane's own key: one per peer and direction. */
    public static final String KEY_SYSTEM = "urn:dbo:lane";

    private LaneModel() {
    }

    public static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, WorkModel.DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(KEY_SYSTEM), Handling.storeAuthored(), extractor(), List.of()));
    }

    /** Peer, epoch and positions. Nothing about what travelled, and nothing about anybody. */
    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Object lane = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(KEY_SYSTEM, Json.str(lane, "key"));
            envelope.value("key", EnvelopeValue.of(Json.str(lane, "key")));
            envelope.value("peer", EnvelopeValue.of(Json.str(lane, "peer")));
            envelope.value("epoch", EnvelopeValue.of(Json.str(lane, "epoch")));
            return envelope;
        };
    }

    public static Identifier key(String key) {
        return new Identifier(KEY_SYSTEM, key);
    }
}
