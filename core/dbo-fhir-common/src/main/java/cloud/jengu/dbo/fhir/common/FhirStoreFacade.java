package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.PutResult;

import java.util.Map;

/**
 * The version-neutral store surface the REST layer serves. Each
 * personality's store implements it; JSON strings and core types only.
 */
public interface FhirStoreFacade {

    PutResult create(String resourceJson);

    /** {@code expectedVersion} null = unconditional update. */
    PutResult update(String id, Long expectedVersion, String resourceJson);

    PutResult conditionalCreate(String resourceJson, Map<String, String> condition);

    String read(String typeName, String id);

    /**
     * A read, with the two facts a serving surface has to put in headers.
     *
     * <p>{@link #read} answers the body alone, which is all a caller inside
     * the JVM wants. HTTP wants more: the specification requires an
     * {@code ETag} on a read and asks for a {@code Last-Modified}, and without
     * them a client cannot do a conditional update after a read — it would
     * have to write blind, or re-fetch through a search to find a version it
     * was just handed.
     *
     * @return null when the resource does not exist, mirroring {@link #read}
     */
    ReadResult readForServing(String typeName, String id);

    /** A resource with its version and the moment it was last written. */
    record ReadResult(String resourceJson, long versionId, java.time.Instant lastUpdated) {}

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
