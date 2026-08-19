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

    /**
     * The operations this store answers, for the router and the statement
     * alike (#51). One list, two readers — that is the whole point.
     */
    default java.util.List<FhirOperation> operations() {
        return java.util.List.of();
    }

    /**
     * The statement, declaring exactly the operations that were registered.
     *
     * <p>The list is passed rather than guessed. An earlier attempt threaded a
     * boolean saying whether terminology was wired, so the generator could
     * infer what the router would answer — which is how the two drift.
     */
    default String capabilityStatement(String baseUrl,
            java.util.Collection<FhirOperation> served) {
        return capabilityStatement(baseUrl);
    }

    /** An OperationOutcome document for error responses. */
    /**
     * Would this resource be accepted — answered without writing it (#48).
     *
     * <p>The verdict comes from the write path's own validation rather than a
     * second implementation of the rules. Two validators would eventually
     * disagree, and the one a caller consulted would not be the one that
     * mattered.
     *
     * <p><b>Shape, not state.</b> The answer is a property of the resource:
     * its profile and the constraints that profile carries. It is not a promise
     * about the world at write time — an identity already claimed, or a version
     * moved on underneath, are answered by the write and cannot honestly be
     * predicted here. A caller who treats this as a reservation will be wrong
     * eventually.
     *
     * @return an {@code OperationOutcome}, issues carrying the locations the
     *         refusal would carry, so somebody is told what to fix
     */
    String validationOutcome(String resourceJson);

    String operationOutcome(String issueCode, String diagnostics);

    /** True when the type is configured in this store's personality. */
    boolean knowsType(String typeName);
}
