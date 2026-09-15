package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Where a switch saying whether a step is automated here actually lives.
 *
 * <p>{@link Automation} described a decision nobody could make. There was a
 * record for it and a resolution rule that honoured it, and nothing that could
 * write one — so "automation is off at this zone" was a sentence the code knew
 * how to read and no deployment could say. A declaration nothing can be
 * written in is the same defect as a toolset nothing constructs, approached
 * from the other side.
 *
 * <p><b>Scoped by the chain, owned by the database.</b> A switch sits at
 * baseline, at a zone or at an organisation, and the most local one wins —
 * which answers <em>where on the chain</em>. It does not need a scope class of
 * its own for "this tenant", because a tenant's records live in that tenant's
 * database: writing one here is what makes it this tenant's, and a second way
 * of saying so would be a fact in two places waiting to disagree.
 *
 * <p><b>Off is a record, not an absence.</b> A step nobody automated and a
 * step somebody switched off look identical while things are working and
 * nothing alike when somebody asks why — which is the whole reason this is
 * declared configuration rather than a deployment that simply never installed
 * a handler.
 */
public final class AutomationModel {

    /** The type name a switch is registered under. */
    public static final String TYPE = "AutomationSwitch";

    /** A switch's own key: one per process, step and scope. */
    public static final String KEY_SYSTEM = "urn:dbo:automation";

    private AutomationModel() {
    }

    public static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, WorkModel.DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(KEY_SYSTEM),
                // Store-authored and versioned: an operator asking when a step
                // stopped being automatic is asking about the history of this
                // record, and a switch that was overwritten in place has none.
                Handling.storeAuthored(), extractor(), List.of()));
    }

    /** One switch per process, step and scope — writing it again replaces it. */
    public static String keyFor(String process, String step, Scope scope) {
        return process + "/" + step + "@" + scope.wire();
    }

    public static Identifier key(String key) {
        return new Identifier(KEY_SYSTEM, key);
    }

    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Object declared = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(KEY_SYSTEM, Json.str(declared, "key"));
            envelope.value("key", EnvelopeValue.of(Json.str(declared, "key")));
            envelope.value("process", EnvelopeValue.of(Json.str(declared, "process")));
            envelope.value("step", EnvelopeValue.of(Json.str(declared, "step")));
            envelope.value("scope", EnvelopeValue.of(Json.str(declared, "scope")));
            envelope.value("on", EnvelopeValue.of(Json.str(declared, "on")));
            return envelope;
        };
    }
}
