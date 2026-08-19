package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.common.FhirOperation;
import cloud.jengu.dbo.fhir.common.FhirTerminology;

import java.util.List;
import java.util.Optional;

/**
 * The terminology surface this face does not have yet, said out loud.
 *
 * <p>It advertises no operations, so nothing routable reaches it and the
 * CapabilityStatement does not announce what the store would refuse
 * (REQ-DBO-SRCH-HONEST-CAPABILITY). Asked anyway — by a caller that went
 * around the router — it refuses by name rather than answering emptily, because
 * "this face cannot expand a value set" and "that value set has no concepts"
 * are different facts with different fixes.
 *
 * <p>The grain codec handles nothing, which is not a stub but the truth: no
 * type is stored here in a reassembled native form, so a stream between two
 * tenants has nothing to take apart (REQ-DBO-CORE-DECLARED-TRUTH-FORM).
 */
final class ElementTerminology implements FhirTerminology {

    private final String code;

    ElementTerminology(String code) {
        this.code = code;
    }

    @Override
    public List<FhirOperation> operations() {
        return List.of();
    }

    @Override
    public IngestResult ingestCodeSystem(String codeSystemJson) {
        throw unsupported("ingest a CodeSystem in its native form");
    }

    @Override
    public PutResult ingestValueSet(String valueSetJson) {
        throw unsupported("ingest a ValueSet");
    }

    @Override
    public Optional<String> lookup(String system, String code) {
        throw unsupported("look a code up");
    }

    @Override
    public String validateCode(String system, String code) {
        throw unsupported("validate a code");
    }

    @Override
    public Optional<String> expand(String valueSetUrl, String filter, int offset, int count) {
        throw unsupported("expand a value set");
    }

    @Override
    public boolean handles(String typeName) {
        return false;
    }

    @Override
    public byte[] forTransport(String typeName, byte[] storedPayload) {
        return storedPayload;
    }

    @Override
    public byte[] receive(String typeName, byte[] transportedPayload) {
        return transportedPayload;
    }

    private UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("the " + code + " face cannot " + what
                + " yet — it declares no terminology operations, so a tenant on this version "
                + "has no terminology surface rather than a silent one");
    }
}
