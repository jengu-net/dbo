package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.face.Coarsening;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.RecordProjection;

import java.util.ArrayList;
import java.util.List;

/**
 * What a tenant's spec requires of its face, refused at bring-up.
 *
 * <p>The declaring half of the face contract was load-bearing and the
 * refusing half did not exist: an absent capability surfaced where it was
 * first needed — an exception in the middle of a request, or worse, a quiet
 * degradation. The PDI path had exactly that scar: built without the face's
 * coarsening, every GENERALISE element silently became a REMOVE, and the
 * capability was published all along.
 *
 * <p><b>Absence is only an error against a requirement.</b> A face is not
 * required to provide everything — that is the point of declaring rather than
 * mandating — so the requirements are derived from what THIS tenant's spec
 * asks for, and a tenant that asks for nothing unusual comes up exactly as it
 * always did. The refusal names both sides: the capability, and the part of
 * the spec that requires it.
 *
 * <p>Same shape as {@code Handling}: declare, and fail closed at
 * registration — before the tenant's database exists, so a refusal leaves
 * nothing behind to clean up.
 */
public final class FaceRequirements {

    /** One requirement: the capability, and which part of the spec asks. */
    private record Requirement(Class<?> capability, String askedBy) {}

    private FaceRequirements() {
    }

    /**
     * Refuses the tenant if its face cannot serve its spec.
     *
     * @throws IllegalStateException naming every unmet requirement at once —
     *         a spec author fixes the face or the spec in one round, not one
     *         refusal at a time
     */
    public static void refuseUnservable(TenantSpec spec, DomainFace face) {
        List<Requirement> required = new ArrayList<>();
        if (!spec.types().isEmpty()) {
            // Declaring any type means serving it: parsing and rendering its
            // payloads, framing search results, and rendering the audit trail
            // the policy layer keeps for it — the audit surface is mounted for
            // every tenant, and its projection's own javadoc calls a face
            // without one "a misconfiguration the refusal names". This is
            // where it gets named.
            required.add(new Requirement(Payloads.class,
                    "types are declared, and a declared type is parsed and rendered"));
            required.add(new Requirement(PayloadFraming.class,
                    "types are declared, and search results are framed"));
            required.add(new Requirement(RecordProjection.class,
                    "types are declared, and the audit trail renders in the domain's words"));
        }
        if (spec.pdi()) {
            // The engine declares THAT an element is generalised; only a face
            // knows a birth date reduces to its year. Built without it, every
            // GENERALISE silently became a REMOVE — the exact
            // late-arriving failure this check exists to move to bring-up.
            required.add(new Requirement(Coarsening.class,
                    "pdi is declared, and a generalised element needs the face's coarsening"));
        }

        List<String> unmet = new ArrayList<>();
        for (Requirement requirement : required) {
            if (face.capability(requirement.capability()).isEmpty()) {
                unmet.add(requirement.capability().getSimpleName()
                        + " — required because " + requirement.askedBy());
            }
        }
        if (!unmet.isEmpty()) {
            throw new IllegalStateException(spec.code() + ": face '" + face.name()
                    + "' cannot serve this spec — missing " + String.join("; ", unmet));
        }
    }
}
