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
import java.util.Map;
import java.util.Set;

/**
 * What a participant says it can run.
 *
 * <p>A tenant is not a process that runs somewhere. It is a store plus the
 * participants that hold its steps — an edge, a hospital's own system, a second
 * server for capacity, a person at a screen — and each of them has to be able
 * to say <b>I am an executor for this step</b>, or resolution cannot see them.
 * A candidate that could only come from a bundle installed in this container
 * makes a tenant a single machine and every remote participant an exception.
 *
 * <p><b>A declaration is a claim to be a candidate, never a grant.</b> What a
 * participant may actually take stays the intersection of its scopes and what
 * the step admits: a step cannot grant its executor more than the
 * executor already holds, and this record cannot either. It is written through
 * the tenant's store like anything else, so a participant whose credential may
 * not write here is refused by the authority rather than by this model.
 *
 * <p>Registration is the same rule one level out: a face is served because a
 * bundle providing it is installed; an executor exists because something
 * announced itself. Neither is a list somebody maintains.
 */
public final class ExecutorModel {

    /** The type name a declaration is registered under. */
    public static final String TYPE = "ExecutorDeclaration";

    /** A declaration's own key: one per step, scope and name. */
    public static final String KEY_SYSTEM = "urn:dbo:executor";

    private ExecutorModel() {
    }

    public static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, WorkModel.DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(KEY_SYSTEM), Handling.storeAuthored(), extractor(), List.of()));
    }

    /**
     * Everything here is state: which step, whose scope, which build, whose
     * code, and the consumer name presence is read from. Nothing about any
     * subject, because a declaration is about a participant rather than about
     * work.
     */
    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Object declaration = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(KEY_SYSTEM, Json.str(declaration, "key"));
            envelope.value("key", EnvelopeValue.of(Json.str(declaration, "key")));
            envelope.value("process", EnvelopeValue.of(Json.str(declaration, "process")));
            envelope.value("step", EnvelopeValue.of(Json.str(declaration, "step")));
            envelope.value("name", EnvelopeValue.of(Json.str(declaration, "name")));
            envelope.value("scope", EnvelopeValue.of(Json.str(declaration, "scope")));
            envelope.value("provider", EnvelopeValue.of(Json.str(declaration, "provider")));
            field(declaration, "consumer").ifPresent(consumer ->
                    envelope.value("consumer", EnvelopeValue.of(consumer)));
            return envelope;
        };
    }

    private static java.util.Optional<String> field(Object declaration, String name) {
        Object value = ((Map<?, ?>) declaration).get(name);
        return value == null ? java.util.Optional.empty() : java.util.Optional.of(value.toString());
    }

    /** The identity a declaration is found by. */
    public static Identifier key(String key) {
        return new Identifier(KEY_SYSTEM, key);
    }
}
