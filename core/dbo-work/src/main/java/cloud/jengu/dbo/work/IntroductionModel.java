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
 * A step declaration that arrived over the link.
 *
 * <p>Modules contribute by being installed; a linked participant is the
 * second contributor kind, and its contribution is this record: the full
 * declaration — id, version, domains, shapes, actions, slots, milestones —
 * with the introducer's name as provenance. Same catalogue, second door,
 * and the collision rule holds across both: one id, one declarer.
 *
 * <p>Kept until withdrawn rather than dropped with presence: a run recorded
 * under an introduced step still needs its declaration to be interpreted
 * while its participant naps. Candidacy is what presence gates, and that is
 * the executor declaration's business, not this record's.
 */
public final class IntroductionModel {

    /** The type name an introduced step is registered under. */
    public static final String TYPE = "StepIntroduction";

    /** An introduction's own key: the step id, globally stable. */
    public static final String KEY_SYSTEM = "urn:dbo:step";

    private IntroductionModel() {
    }

    public static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, WorkModel.DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(KEY_SYSTEM), Handling.storeAuthored(), extractor(), List.of()));
    }

    /**
     * State about a step, nothing about a subject: the id it claims, and who
     * introduced it — the two facts a collision refusal and a provenance
     * question read.
     */
    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Object introduction = Json.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(KEY_SYSTEM, Json.str(introduction, "id"));
            envelope.value("id", EnvelopeValue.of(Json.str(introduction, "id")));
            envelope.value("introducer", EnvelopeValue.of(Json.str(introduction, "introducer")));
            return envelope;
        };
    }

    /** The identity an introduction is found by. */
    public static Identifier key(String stepId) {
        return new Identifier(KEY_SYSTEM, stepId);
    }
}
