package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.model.ElementDefinition;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.utils.validation.IMessagingServices;
import org.hl7.fhir.r5.utils.validation.IResourceValidator;
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.instance.advisor.BasePolicyAdvisorForFullValidation;

import java.util.List;

/**
 * What a write is held to: the type's definition, and the profiles the author
 * declared. Not more.
 *
 * <p>Two rules, and both are about a store rather than about a validator.
 *
 * <p><b>References are not followed.</b> Checking that a referenced object
 * exists would make a write depend on what else is in the store at that
 * instant — the same two writes accepted in one order and refused in the other,
 * and a resource pointing at something arriving a second later refused for
 * being early. Referential closure is the engine's, checked where it can be
 * checked consistently (REQ-DBO-CORE-REFERENCE-CLOSURE).
 *
 * <p><b>No profile is inferred from content.</b> The toolchain will otherwise
 * recognise, say, an Observation carrying a heart-rate LOINC code and hold it
 * to the vital-signs profile the author never claimed — so a resource is
 * refused for not conforming to something it did not say it was. What a
 * resource is validated against is what it declares
 * (REQ-DBO-VER-SPECIFIED-VALIDATION), and a tenant that wants a profile
 * enforced says so in {@code meta.profile}.
 */
final class ElementValidationPolicy extends BasePolicyAdvisorForFullValidation {

    ElementValidationPolicy() {
        super(ReferenceValidationPolicy.IGNORE, java.util.Set.of());
    }

    @Override
    public List<StructureDefinition> getImpliedProfilesForResource(IResourceValidator validator,
            Object appContext, String stackPath, ElementDefinition definition,
            StructureDefinition structure, Element resource, boolean valid,
            IMessagingServices messaging, List<ValidationMessage> messages) {
        return List.of();
    }
}
