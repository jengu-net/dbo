package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.face.Coarsening;
import cloud.jengu.dbo.core.face.DeclaredFace;
import cloud.jengu.dbo.core.face.DomainFace;

/**
 * What the FHIR face provides to the engine.
 *
 * <p>Version-neutral: R4 and R5 agree about the shapes these capabilities
 * concern — a {@code date} is a {@code date} in both — so both personalities
 * declare from here. Where they diverge, a personality declares its own face
 * with its own implementation rather than the two negotiating inside one.
 */
public final class FhirFace {

    private FhirFace() {
    }

    /**
     * @param version the personality's name, so a refusal says which of two
     *                parallel faces could not serve the request
     */
    public static DomainFace of(String version) {
        return DeclaredFace.named("fhir-" + version)
                .providing(Coarsening.class, FhirCoarsening.INSTANCE)
                .build();
    }
}
