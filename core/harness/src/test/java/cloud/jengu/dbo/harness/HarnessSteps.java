package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;

import java.util.Set;

/**
 * The steps this harness contributes by being on the classpath (#71) — which
 * is how a module contributes them anywhere.
 */
public final class HarnessSteps implements Steps.Catalogue {

    /** A step whose input is narrower than the type's own shape. */
    public static final String VITALS = "lab.result.record-vitals";

    @Override
    public Set<StepDeclaration> steps() {
        return Set.of(
                StepDeclaration.of(VITALS, "1.0", "r4")
                        .consuming("http://hl7.org/fhir/StructureDefinition/vitalsigns"),
                StepDeclaration.of("lab.result.dispatch", "1.0", "r4"));
    }
}
