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
 * Why a record is on this appliance: it came with a piece of work (#80).
 *
 * <p>Work-driven arrival implies work-driven expiry. Without a note of what
 * brought a record here, an appliance cannot tell a copy that arrived for a
 * task from something of its own, and the only safe answer is to keep
 * everything — which is a bench accumulating a register one task at a time,
 * the outcome the whole rule exists to prevent.
 *
 * <p><b>The reference is in the record and never in the envelope.</b> What is
 * indexed is the run and the type; an envelope naming the subject would tell
 * everyone who can see that work exists who it is about.
 */
public final class PlacementModel {

    public static final String TYPE = "Placement";

    public static final String KEY_SYSTEM = "urn:dbo:placement";

    private PlacementModel() {
    }

    public static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, WorkModel.DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(KEY_SYSTEM), Handling.storeAuthored(), extractor(), List.of()));
    }

    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Object placement = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(KEY_SYSTEM, Json.str(placement, "key"));
            envelope.value("key", EnvelopeValue.of(Json.str(placement, "key")));
            envelope.value("run", EnvelopeValue.of(Json.str(placement, "run")));
            envelope.value("type", EnvelopeValue.of(Json.str(placement, "type")));
            return envelope;
        };
    }

    public static Identifier key(String key) {
        return new Identifier(KEY_SYSTEM, key);
    }
}
