package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.common.FhirOperation;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.element.ElementFhirVersion;
import cloud.jengu.dbo.fhir.element.ElementStore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The FHIR R5 store surface, served by the shared facade (#58).
 *
 * <p>This used to be 278 lines that {@code R4Store} repeated almost exactly —
 * validate, put, read, search, history, conditional create — none of which is
 * knowledge about R5. All of it now runs through {@link ElementStore}, which
 * asks the face what a payload means and the definitions what a parameter is,
 * so R4 and R5 and R6 answer through one implementation rather than three.
 *
 * <p>What is left here is the name and the constructor a caller already holds.
 * The one thing this adds is {@code $validate}, which is registered rather than
 * routed by name (#51) — an operation is declared because it is reachable.
 */
public final class R5Store implements FhirStoreFacade {

    private final R5Personality personality;
    private final ElementStore served;

    public R5Store(ObjectStore store, R5Personality personality, String baseUrl) {
        this.personality = personality;
        this.served = (ElementStore) R5FhirVersion.INSTANCE
                .forTypes(personality.typeConfigs()).store(store, baseUrl);
    }

    /**
     * What this store answers (#51): {@code $validate}, on every type it serves.
     *
     * <p>Registered rather than routed by name, so it is announced by the same
     * act that makes it reachable.
     */
    @Override
    public List<FhirOperation> operations() {
        return List.of(new FhirOperation() {
            @Override
            public String name() {
                return "validate";
            }

            @Override
            public String definition() {
                return "http://hl7.org/fhir/OperationDefinition/Resource-validate";
            }

            @Override
            public java.util.Set<String> types() {
                return personality.configuredTypes();
            }

            @Override
            public Answer answer(String typeName, Map<String, String> query, String body) {
                String mode = query.getOrDefault("mode", "create");
                if (!"create".equals(mode) && !"update".equals(mode)) {
                    return Answer.status(400, operationOutcome("invalid",
                            "unsupported $validate mode: " + mode));
                }
                // 200 whatever the verdict: a caller who asked correctly did
                // not make a bad request, and the outcome carries the answer.
                return Answer.ok(validationOutcome(body));
            }
        });
    }

    /** Conditional upsert of a canonical artifact by its url (validated). */
    public PutResult putCanonical(String resourceJson) {
        return served.putCanonical(resourceJson);
    }

    @Override
    public PutResult create(String resourceJson) {
        return served.create(resourceJson);
    }

    @Override
    public PutResult update(String id, Long expectedVersion, String resourceJson) {
        return served.update(id, expectedVersion, resourceJson);
    }

    @Override
    public PutResult conditionalCreate(String resourceJson, Map<String, String> condition) {
        return served.conditionalCreate(resourceJson, condition);
    }

    @Override
    public String read(String typeName, String id) {
        return served.read(typeName, id);
    }

    @Override
    public ReadResult readForServing(String typeName, String id) {
        return served.readForServing(typeName, id);
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion) {
        served.delete(typeName, id, expectedVersion);
    }

    @Override
    public String search(String typeName, Map<String, String> params, String cursor) {
        return served.search(typeName, params, cursor);
    }

    @Override
    public void search(String typeName, Map<String, String> params, String cursor,
            OutputStream out) throws IOException {
        served.search(typeName, params, cursor, out);
    }

    @Override
    public String historyBundle(String typeName, String id) {
        return served.historyBundle(typeName, id);
    }

    @Override
    public String capabilityStatement(String baseUrl) {
        return served.capabilityStatement(baseUrl);
    }

    @Override
    public String capabilityStatement(String baseUrl, Collection<FhirOperation> served0) {
        return served.capabilityStatement(baseUrl, served0);
    }

    @Override
    public String validationOutcome(String resourceJson) {
        return served.validationOutcome(resourceJson);
    }

    @Override
    public String operationOutcome(String issueCode, String diagnostics) {
        return served.operationOutcome(issueCode, diagnostics);
    }

    @Override
    public boolean knowsType(String typeName) {
        return served.knowsType(typeName);
    }
}
