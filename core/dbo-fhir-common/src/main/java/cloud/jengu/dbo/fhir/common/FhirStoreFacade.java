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

    /**
     * Conditional update (R4 §3.1.0.7.1): {@code PUT [type]?[search]}.
     *
     * <p>The primitive for "this resource, identified by its canonical, should
     * exist with these contents" — absent it is created, present it is
     * replaced. Conditional CREATE cannot stand in: it is a no-op when the
     * resource exists, so a definition changed upstream keeps its old
     * contents and the caller is told it worked, which is worse than a
     * refusal (#99).
     */
    default PutResult conditionalUpdate(String resourceJson, Map<String, String> condition) {
        throw new UnsupportedOperationException("this store does not accept conditional updates");
    }

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

    /**
     * The same searchset, written as it is produced rather than returned whole
     * (REQ-DBO-SRCH-RESULTS-STREAM).
     *
     * <p>Returning a String means a page exists three times before a reader
     * sees any of it — the payloads, whatever the face built from them, and the
     * encoded copy. Writing into the caller's stream leaves one member in
     * flight, which is what makes a page, an export and a stream between
     * tenants the same path rather than three.
     *
     * <p>The default answers the old way, so a face that has not implemented
     * this is slower and never wrong.
     */
    default void search(String typeName, Map<String, String> params, String cursor,
            java.io.OutputStream out) throws java.io.IOException {
        out.write(search(typeName, params, cursor)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

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

    /**
     * The same, told which types are served by a surface of their own and
     * with which search parameters — the audit trail today (#90).
     *
     * <p>Passed in rather than assumed: the thing that implements a filter is
     * the only honest source for which filters exist, and a capability that
     * listed every parameter a version defines would be advertising work
     * nothing does.
     */
    default String capabilityStatement(String baseUrl,
            java.util.Collection<FhirOperation> served,
            java.util.Map<String, java.util.Set<String>> narrowedSearch) {
        return capabilityStatement(baseUrl, served);
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

    /**
     * The same question against a <b>named</b> shape (#49).
     *
     * <p>A resource is often assembled <em>for a step</em>, and the step's
     * shape is narrower than the type's — so a caller who can only ask the
     * weaker question gets a resource that passes {@code $validate} and is then
     * refused by the step that receives it. That is the same "two answers, and
     * the one you asked was not the one that mattered" this operation exists to
     * remove, one level up.
     *
     * @param profile a shape this store knows: a canonical the face carries, or
     *                the id of a declared step, whose input shape it resolves
     *                to. A profile nothing here declares is refused rather than
     *                fetched — a caller wanting an arbitrary published IG is
     *                asking for a validation service, not for this store's
     *                opinion about its own content.
     */
    default String validationOutcome(String resourceJson, String profile) {
        return validationOutcome(resourceJson);
    }

    /**
     * A transaction or batch Bundle posted to the base, answered as a
     * response bundle (#86). The default refuses by name — a facade that has
     * not implemented bundles answers "not offered here", never a 500.
     */
    default String bundle(String bundleJson) {
        throw new UnsupportedOperationException(
                "this endpoint does not process bundles");
    }

    /**
     * A StructureDefinition reached this tenant's store by some path other than
     * this facade — replication from a zone, an archive restored, the engine
     * written directly — and the shapes this facade validates against are now
     * older than the store it validates for (#87).
     *
     * <p>Writes THROUGH the facade rebuild the view themselves, at the write.
     * This is the other half, and it exists because a facade cannot notice what
     * it did not do. The runtime that owns the tenant watches its change feed
     * and says so; the facade decides what that means.
     *
     * <p>A no-op by default: a face whose validation carries no tenant shapes
     * has nothing to rebuild, and should not be made to pretend otherwise.
     */
    default void shapesChanged() {
    }

    String operationOutcome(String issueCode, String diagnostics);

    /**
     * An outcome for a fault in THIS STORE, as opposed to a finding about the
     * caller's content (#105).
     *
     * <p>The two were indistinguishable without reading the diagnostics string,
     * so an internal fault was attributed to whoever posted the document. The
     * default keeps a facade that has not thought about it no worse than it
     * was; a facade that has says {@code fatal}.
     */
    default String internalFault(String diagnostics) {
        return operationOutcome("exception", diagnostics);
    }

    /** True when the type is configured in this store's personality. */
    boolean knowsType(String typeName);
}
