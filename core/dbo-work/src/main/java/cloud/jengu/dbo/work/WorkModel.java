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
 * The run sibling model (#46): a run is a <b>regular record</b> in the tenant's
 * own store — envelope-queryable, versioned, feed-visible, carried by the
 * backup, dropped with the tenant.
 *
 * <p>Registration is the whole argument. A private table confers none of those:
 * it is visible in the sense that rows exist, and invisible in every sense that
 * matters, which is why a dead-letter row cannot be seen or acted on today
 * (REQ-DBO-PROC-RUN-HAS-A-RECORD).
 *
 * <p>Domain {@code work}, so operational history has its own outbox and its own
 * export, and never masquerades as clinical work in a clinical view.
 */
public final class WorkModel {

    public static final String DOMAIN = "work";

    /** A run's own key, so a sweep can be found again rather than started again. */
    public static final String KEY_SYSTEM = "urn:dbo:run";

    /** The type name a run is registered under. Not a FHIR type; the face renders that. */
    public static final String TYPE = "Run";

    private WorkModel() {
    }

    /**
     * The work domain's types: what ran, and who said they could run it
     * ({@link ExecutorModel}). Together, because a store that holds runs and
     * cannot hold declarations can only ever be executed by whatever is
     * installed beside it.
     */
    public static List<TypeRegistration> registrations() {
        List<TypeRegistration> all = new java.util.ArrayList<>(List.of(
                new TypeRegistration(TYPE, DOMAIN, IdentityClass.IDENTIFIER,
                        Set.of(KEY_SYSTEM), handling(), extractor(), List.of())));
        all.addAll(ExecutorModel.registrations());
        return List.copyOf(all);
    }

    /**
     * Authored by the runtime, versioned at checkpoints, and it rides the
     * backup rather than an export: a run is this deployment's account of what
     * it did, not part of what a tenant takes with them when they leave.
     */
    private static Handling handling() {
        return Handling.storeAuthored();
    }

    /**
     * <b>State, never subject</b> (REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT).
     *
     * <p>Holder, step, kind and counts are indexed, so "what is waiting for a
     * person" is a query. What a run was <em>about</em> is not: an envelope is
     * a disclosure surface, readable by parties entitled to route on it and not
     * to read payloads, so an item reference promoted into it would put the
     * subject of the work in front of everyone who can see that work exists.
     * The record still carries it, for whoever may open the record.
     */
    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Object run = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(KEY_SYSTEM, Json.str(run, "key"));
            envelope.value("key", EnvelopeValue.of(Json.str(run, "key")));
            envelope.value("process", EnvelopeValue.of(Json.str(run, "process")));
            envelope.value("step", EnvelopeValue.of(Json.str(run, "step")));
            envelope.value("kind", EnvelopeValue.of(Json.str(run, "kind")));
            envelope.value("holder", EnvelopeValue.of(Json.str(run, "holder")));
            field(run, "parent").ifPresent(parent ->
                    envelope.value("parent", EnvelopeValue.of(parent)));
            // Echoed, never parsed: a cross-system join queryable from either
            // side, without that system's vocabulary entering the engine.
            // Where the work happened and what ran it. State, not subject: an
            // operator counting the fall-through per step and per zone is the
            // automation backlog, and it must not cost a payload read.
            field(run, "scope").ifPresent(scope ->
                    envelope.value("scope", EnvelopeValue.of(scope)));
            if (((Map<?, ?>) run).get("executor") instanceof Map<?, ?> executor
                    && executor.get("name") != null) {
                envelope.value("executor", EnvelopeValue.of(executor.get("name").toString()));
            }
            field(run, "correlation").ifPresent(correlation ->
                    envelope.value("correlation", EnvelopeValue.of(correlation)));
            counts(run, envelope);
            return envelope;
        };
    }

    private static void counts(Object run, Envelope envelope) {
        Object tally = ((Map<?, ?>) run).get("tally");
        if (!(tally instanceof Map<?, ?> counts)) {
            return;
        }
        counts.forEach((name, value) -> {
            if (value instanceof Number count) {
                envelope.value("tally_" + name, EnvelopeValue.of(count.longValue()));
            }
        });
    }

    private static java.util.Optional<String> field(Object run, String name) {
        Object value = ((Map<?, ?>) run).get(name);
        return value == null ? java.util.Optional.empty() : java.util.Optional.of(value.toString());
    }

    /** The identity a run is found by — a sweep's is stable, a pipeline's is its own. */
    public static Identifier key(String key) {
        return new Identifier(KEY_SYSTEM, key);
    }
}
