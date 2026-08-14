package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.PutResult;

import java.util.Map;

/**
 * The version-neutral store surface the REST layer serves (dbo#12). Each
 * personality's store implements it; JSON strings and core types only.
 */
public interface FhirStoreFacade {

    PutResult create(String resourceJson);

    /** {@code expectedVersion} null = unconditional update. */
    PutResult update(String id, Long expectedVersion, String resourceJson);

    PutResult conditionalCreate(String resourceJson, Map<String, String> condition);

    String read(String typeName, String id);

    void delete(String typeName, String id, Long expectedVersion);

    /** Searchset Bundle; cursor from a previous page's link[next]. */
    String search(String typeName, Map<String, String> params, String cursor);

    /** History Bundle, oldest first. Empty entries if the id is unknown. */
    String historyBundle(String typeName, String id);

    /** CapabilityStatement generated from the configured types (REQ-DBO-SRCH-HONEST-CAPABILITY). */
    String capabilityStatement(String baseUrl);

    /** An OperationOutcome document for error responses. */
    String operationOutcome(String issueCode, String diagnostics);

    /** True when the type is configured in this store's personality. */
    boolean knowsType(String typeName);
}
