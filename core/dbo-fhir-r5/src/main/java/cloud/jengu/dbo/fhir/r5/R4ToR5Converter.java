package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.api.PayloadConverter;

/**
 * The R4→R5 hop over HL7's own version convertors. Runs at READ —
 * stored bytes stay R4 (payload-is-truth); identity fields survive because the
 * HL7 convertor maps `url` and `identifier` structurally
 * (REQ-DBO-CORE-IDENTITY-SURVIVES-CONVERSION — asserted in the harness).
 * Public surface is HAPI/hl7-free per the §7.3 boundary ratchet.
 */
public final class R4ToR5Converter implements PayloadConverter {

    @Override
    public String fromVersion() {
        return "4.0";
    }

    @Override
    public String toVersion() {
        return R5Personality.PAYLOAD_VERSION;
    }

    @Override
    public byte[] convert(String typeName, byte[] payload) {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        // the loader that owns the convertors, which is the shared stack bundle
        // rather than this personality
        t.setContextClassLoader(org.hl7.fhir.r4.model.Resource.class.getClassLoader());
        try {
            org.hl7.fhir.r4.model.Resource r4 =
                    (org.hl7.fhir.r4.model.Resource) new org.hl7.fhir.r4.formats.JsonParser().parse(payload);
            org.hl7.fhir.r5.model.Resource r5 =
                    (org.hl7.fhir.r5.model.Resource) org.hl7.fhir.convertors.factory
                            .VersionConvertorFactory_40_50.convertResource(r4);
            return new org.hl7.fhir.r5.formats.JsonParser().composeBytes(r5);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("R4→R5 conversion failed for " + typeName, e);
        } finally {
            t.setContextClassLoader(old);
        }
    }
}
