package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.fhirpath.FHIRPathEngine;
import org.hl7.fhir.r5.fhirpath.FHIRPathUtilityClasses;
import org.hl7.fhir.r5.fhirpath.IHostApplicationServices;
import org.hl7.fhir.r5.fhirpath.TypeDetails;
import org.hl7.fhir.r5.model.Base;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.utilities.fhirpath.FHIRPathConstantEvaluationMode;

import java.util.List;

/**
 * What the validator and the path engine may ask the host for, answered by a
 * store that will not go looking.
 *
 * <p>A reference is not resolved. The validator asks in order to check that a
 * referenced object exists and is of the right type; doing that on the accept
 * path would make a write depend on what else is in the store at that instant —
 * so a resource pointing at something not yet written would be refused, and the
 * same two writes in the other order would both be accepted. Referential
 * closure is the engine's, checked where it can be checked consistently
 * (REQ-DBO-CORE-REFERENCE-EDGES), not a validator's guess.
 *
 * <p>Answering "not resolved" is different from not being asked: without a host
 * at all, a search parameter using {@code resolve()} and a profile that
 * references anything both fail outright with "Resource resolution services not
 * provided", which reads as a broken store rather than an unresolved reference.
 *
 * <p>A value set, on the other hand, is resolved: it is definitional, and the
 * definitions travel with the face.
 */
final class ElementHostServices implements IHostApplicationServices {

    private final SimpleWorkerContext context;

    ElementHostServices(SimpleWorkerContext context) {
        this.context = context;
    }

    @Override
    public List<Base> resolveConstant(FHIRPathEngine engine, Object appContext, String name,
            FHIRPathConstantEvaluationMode mode) {
        return List.of();
    }

    @Override
    public TypeDetails resolveConstantType(FHIRPathEngine engine, Object appContext, String name,
            FHIRPathConstantEvaluationMode mode) {
        return null;
    }

    @Override
    public boolean log(String argument, List<Base> focus) {
        return false;
    }

    @Override
    public FHIRPathUtilityClasses.FunctionDetails resolveFunction(FHIRPathEngine engine,
            String functionName) {
        return null;
    }

    @Override
    public TypeDetails checkFunction(FHIRPathEngine engine, Object appContext,
            String functionName, TypeDetails focus, List<TypeDetails> parameters) {
        return null;
    }

    @Override
    public List<Base> executeFunction(FHIRPathEngine engine, Object appContext, List<Base> focus,
            String functionName, List<List<Base>> parameters) {
        return List.of();
    }

    /** Not resolved, and said so rather than failing — see the class note. */
    @Override
    public Base resolveReference(FHIRPathEngine engine, Object appContext, String url,
            Identifier identifier, Base refContext) {
        return null;
    }

    @Override
    public Base findContainingResource(Object appContext, Base base) {
        return null;
    }

    @Override
    public boolean conformsToProfile(FHIRPathEngine engine, Object appContext, Base item,
            String url) {
        // Nothing is resolved, so nothing can be shown not to conform: saying
        // yes here would let a profile assertion pass on evidence never seen.
        return false;
    }

    @Override
    public ValueSet resolveValueSet(FHIRPathEngine engine, Object appContext, String url) {
        return context.fetchResource(ValueSet.class, url);
    }

    @Override
    public boolean paramIsType(String name, int index) {
        return false;
    }
}
