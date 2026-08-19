package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.PutResult;

import java.util.Optional;

/**
 * The terminology operations the REST layer exposes when wired — and the way
 * in that makes them answerable.
 *
 * <p>Ingest is on this interface rather than on each face because writing a
 * CodeSystem is not an ordinary create: the metadata shell goes through the
 * engine and the concepts go to the native form. A write that took the
 * ordinary path would store a full-fat resource nothing can $expand, and the
 * failure is silent — the resource is there, the operation just answers
 * nothing (REQ-DBO-TERM-EVERY-TENANT-ANSWERS).
 */
public interface TerminologyFacade {

    /**
     * The operations this facade answers (#51). A facade that was never wired
     * is never asked, so nothing it offers is routed or declared — the absence
     * says itself.
     */
    java.util.List<FhirOperation> operations();

    /** What an ingest did: the engine's result, and how many concepts landed. */
    record IngestResult(String id, long versionId, long conceptCount) {}

    /** Shell through the engine, concepts through COPY. */
    IngestResult ingestCodeSystem(String codeSystemJson);

    /** The resource through the engine, its compose into the native form. */
    PutResult ingestValueSet(String valueSetJson);

    Optional<String> lookup(String system, String code);

    String validateCode(String system, String code);

    Optional<String> expand(String valueSetUrl, String filter, int offset, int count);
}
