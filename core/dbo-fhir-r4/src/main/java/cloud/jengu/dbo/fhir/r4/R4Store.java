package cloud.jengu.dbo.fhir.r4;

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
 * The FHIR R4 store surface, served by the shared facade (#58).
 *
 * <p>This used to be 278 lines that {@code R5Store} repeated almost exactly —
 * validate, put, read, search, history, conditional create — none of which is
 * knowledge about R4. All of it now runs through {@link ElementStore}, which
 * asks the face what a payload means and the definitions what a parameter is,
 * so R4 and R5 and R6 answer through one implementation rather than three.
 *
 * <p>What is left here is the name and the constructor a caller already holds.
 * The one thing this adds is {@code $validate}, which is registered rather than
 * routed by name (#51) — an operation is declared because it is reachable.
 */
public final class R4Store implements FhirStoreFacade {

    private final R4Personality personality;
    private final ElementStore served;

    public R4Store(ObjectStore store, R4Personality personality, String baseUrl) {
        this.personality = personality;
        this.served = (ElementStore) R4FhirVersion.INSTANCE
                .forTypes(personality.typeConfigs()).store(store, baseUrl);
    }

    /**
     * What this store answers (#51): {@code $validate}, on every type it serves.
     *
     * <p>Registered rather than routed by name, so it is announced by the same
     * act that makes it reachable.
     */
    /**
     * What this store answers (#51), which is what the face it is served by
     * answers.
     *
     * <p>It used to be declared here, in each personality, while the runtime
     * built the face's store — so the operation was announced twice and
     * reachable through neither (#49). One implementation, for every version
     * the face serves.
     */
    @Override
    public List<FhirOperation> operations() {
        return served.operations();
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
    public PutResult conditionalUpdate(String resourceJson, Map<String, String> condition) {
        return served.conditionalUpdate(resourceJson, condition);
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
    public String validationOutcome(String resourceJson, String profile) {
        return served.validationOutcome(resourceJson, profile);
    }

    @Override
    public String bundle(String bundleJson) {
        return served.bundle(bundleJson);
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
