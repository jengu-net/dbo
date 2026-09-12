package cloud.jengu.dbo.fhir.r4;

import cloud.jengu.dbo.core.api.PayloadConverter;

/**
 * The R5→R4 hop over HL7's own version convertors — the mirror of the R4→R5
 * one, over the same factory, which has always converted both ways.
 *
 * <p><b>Why the other direction is needed at all.</b> A zone publishes in one
 * version and its tenants are on whichever face each of them chose, so a zone
 * written in R5 has R4 tenants that must enforce its profiles as R4
 * definitions, against R4 records. Until now this store could only carry a
 * version forward, which meant a zone could serve a face newer than itself and
 * not one older.
 *
 * <p><b>Converting down loses things, and that is the point of the checks
 * around it rather than of this class.</b> A structure constraining an element
 * R5 has and R4 does not cannot be expressed in R4, and the convertor may drop
 * it without saying so. This does what the convertor does and reports what it
 * refuses; deciding whether what came out is still the definition it was meant
 * to be belongs to whoever re-expands it on the target face, because that is
 * where the answer is checkable.
 *
 * <p>Public surface stays HAPI-free, and the context classloader is the shared
 * stack's for the length of the call, because the convertors resolve their own
 * classes through it and this personality's loader does not own them.
 */
public final class R5ToR4Converter implements PayloadConverter {

    @Override
    public String fromVersion() {
        return R5_PAYLOAD_VERSION;
    }

    /**
     * Spelt rather than taken from the R5 personality: this bundle has no
     * business depending on that one to know the number it converts FROM, and
     * the two would then have to be deployed together to agree about it.
     */
    private static final String R5_PAYLOAD_VERSION = "5.0";

    @Override
    public String toVersion() {
        return R4Personality.PAYLOAD_VERSION;
    }

    @Override
    public byte[] convert(String typeName, byte[] payload) {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(org.hl7.fhir.r4.model.Resource.class.getClassLoader());
        try {
            org.hl7.fhir.r5.model.Resource r5 =
                    (org.hl7.fhir.r5.model.Resource) new org.hl7.fhir.r5.formats.JsonParser()
                            .parse(payload);
            org.hl7.fhir.r4.model.Resource r4 =
                    (org.hl7.fhir.r4.model.Resource) org.hl7.fhir.convertors.factory
                            .VersionConvertorFactory_40_50.convertResource(r5);
            return new org.hl7.fhir.r4.formats.JsonParser().composeBytes(r4);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("R5→R4 conversion failed for " + typeName, e);
        } finally {
            t.setContextClassLoader(old);
        }
    }
}
