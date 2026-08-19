package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.face.GrainCodec;

/**
 * A version's terminology surface: what a caller asks ({@link TerminologyFacade})
 * and what a stream between two tenants needs ({@link GrainCodec}).
 *
 * <p>One type rather than two because they are one object — a CodeSystem is
 * stored as concepts and reassembled on the way out, so the facade that answers
 * {@code $expand} and the codec that reassembles a stored form for transport
 * read the same native form (REQ-DBO-TERM-OPERATIONS-FROM-NATIVE-FORM). Naming
 * the pair is what lets a version be resolved rather than constructed by a
 * caller that knows which class to ask for.
 */
public interface FhirTerminology extends TerminologyFacade, GrainCodec {
}
