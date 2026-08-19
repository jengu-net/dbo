package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PortableRendering;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTerminology;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.terminology.TerminologyStore;

import javax.sql.DataSource;
import java.util.List;

/**
 * FHIR R5 as the tenant runtime asks for it.
 *
 * <p>Everything here already existed; what is new is that a caller can have it
 * without naming a class of this bundle. That is the whole of the change: the
 * wiring resolves a version under its code and builds the same objects it used
 * to build inside an {@code if}.
 */
public final class R5FhirVersion implements FhirVersion {

    public static final R5FhirVersion INSTANCE = new R5FhirVersion();

    @Override
    public String code() {
        return R5Personality.DOMAIN;
    }

    @Override
    public String domain() {
        return R5Personality.DOMAIN;
    }

    @Override
    public String payloadVersion() {
        return R5Personality.PAYLOAD_VERSION;
    }

    @Override
    public DomainFace face() {
        return R5Version.face();
    }

    @Override
    public ForTypes forTypes(List<FhirTypeConfig> types) {
        return new Tenant(new R5Personality(types));
    }

    /**
     * One tenant's declared types, and the personality built from them held
     * once — the pieces of a bring-up share it rather than each constructing
     * their own, which is what used to make a tenant cost two.
     */
    private record Tenant(R5Personality personality) implements ForTypes {

        @Override
        public List<TypeRegistration> registrations() {
            return personality.registrations();
        }

        @Override
        public List<TypeRegistration> registrations(String domain) {
            return personality.registrations(domain);
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl) {
            return new R5Store(engine, personality, baseUrl);
        }

        @Override
        public FhirTerminology terminology(ObjectStore engine, DataSource dataSource) {
            return new R5Terminology(engine, personality, new TerminologyStore(dataSource));
        }

        @Override
        public PortableRendering portableRendering() {
            return personality.portableRendering();
        }
    }
}
