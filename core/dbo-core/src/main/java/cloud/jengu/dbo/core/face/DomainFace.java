package cloud.jengu.dbo.core.face;

import java.util.Optional;
import java.util.Set;

/**
 * What the engine requires <b>from</b> a domain face — the mirror of the
 * facades a face offers outward (jengu-platform ADR 0057, dbo#38).
 *
 * <p>The outward facades are what the world calls: an HTTP server holds a
 * {@code FhirStoreFacade} and serves requests through it. This is the other
 * direction. The engine owns the logic — what is stored, how it is identified,
 * what may be written, what is audited — and delegates the parts that are
 * knowledge about a domain's shapes. Whether a birth date reduces to its year,
 * whether an audit entry is spelled {@code AuditEvent}, how a subject identity
 * looks as a resource: none of that is the engine's to know.
 *
 * <p>Those obligations existed before this interface did, scattered across a
 * type registration, a personality and — for a while — a class in the engine
 * that knew about FHIR dates. A contract with no name is a contract nobody
 * notices they have broken.
 *
 * <h2>Capabilities are declared, and looked up by type</h2>
 *
 * <p>A face says what it provides. A face with no terminology, or no
 * subscriptions, provides nothing for them rather than stubbing them: a stub
 * is a lie the engine cannot tell apart from a broken implementation.
 *
 * <p>Lookup is by capability <b>type</b> rather than by a method per
 * capability, and that is the answer to how this contract evolves. Faces move
 * independently — that is the whole point of having them — so adding a method
 * to a shared interface would break every face at once, at exactly the moment
 * a new FHIR version arrives. A new capability is a new type: faces that do
 * not know about it simply do not provide it, and the engine refuses the
 * requests that need it.
 *
 * <h2>A translator, not an actor</h2>
 *
 * <p>Every capability is a pure transformation — data in, data out. None reads
 * or writes the store, and the engine never re-enters itself through one.
 * Without that rule a face could act inside an engine transaction, and two
 * faces running in parallel over one store could disagree about who did what.
 */
public interface DomainFace {

    /** Which face this is — {@code fhir-r4}, {@code fhir-r5}. Used in refusals. */
    String name();

    /** The capability types this face provides. */
    Set<Class<?>> capabilities();

    /**
     * @return the capability, or empty when this face does not provide it —
     *         which is an ordinary answer, not a failure
     */
    <T> Optional<T> capability(Class<T> type);

    /**
     * The capability, or a refusal naming the face and what it was missing.
     *
     * <p>For the engine paths that cannot proceed without one. The refusal is
     * specific because "unsupported" and "misconfigured" look identical from
     * the outside, and a caller that cannot tell them apart cannot act.
     */
    default <T> T require(Class<T> type) {
        return capability(type).orElseThrow(() -> new CapabilityMissingException(name(), type));
    }

    /** The face was asked for something it never said it could do. */
    class CapabilityMissingException extends RuntimeException {
        public CapabilityMissingException(String face, Class<?> type) {
            super("face '" + face + "' provides no " + type.getSimpleName()
                    + " — it was never declared, so this request cannot be served rather than "
                    + "being served wrongly");
        }
    }
}
