package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PortableRendering;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTerminology;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.element.ElementFhirVersion;
import cloud.jengu.dbo.terminology.TerminologyStore;

import javax.sql.DataSource;
import java.util.List;

/**
 * FHIR R5 as the tenant runtime asks for it — served by the shared face (#58).
 *
 * <p>Reading, validating, framing, extracting and serving are the element
 * face's, over R5's own definitions: one implementation for every version
 * rather than one per version. What stays R4's own is what genuinely differs —
 * terminology, whose native form is this version's, and subscriptions, whose
 * topics are R5 semantics rather than an R5 model.
 *
 * <p>The payload version stays {@code 4.0} rather than becoming the
 * definitions' {@code 4.0.1}: it is the coordinate stored rows and the
 * converters already agree on, and a released version's coordinate is not this
 * work's to change.
 */
public final class R5FhirVersion implements FhirVersion {

    public static final R5FhirVersion INSTANCE = new R5FhirVersion();

    private final ElementFhirVersion served =
            ElementFhirVersion.serving(R5Personality.DOMAIN, R5Personality.PAYLOAD_VERSION);

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
        return served.face();
    }

    @Override
    public ForTypes forTypes(List<FhirTypeConfig> types) {
        return new Tenant(served.forTypes(types), new R5Personality(types));
    }

    /** The shared half, and the half that is still this version's. */
    private record Tenant(ForTypes served, R5Personality personality) implements ForTypes {

        @Override
        public List<TypeRegistration> registrations() {
            return served.registrations();
        }

        @Override
        public List<TypeRegistration> registrations(String domain) {
            return served.registrations(domain);
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl) {
            return served.store(engine, baseUrl);
        }

        /**
         * With the tenant's database, forwarded rather than dropped.
         *
         * <p>A personality serves THROUGH the element face, so anything that
         * face needs the tenant's own data for — its terminology (#50), its
         * StructureDefinitions (#87) — arrives only if this forwards it. It
         * did not, which meant a tenant validated against the carried pack
         * alone the moment it declared r4 or r5, while an r6 tenant beside it
         * validated against its own.
         */
        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl,
                javax.sql.DataSource dataSource) {
            return served.store(engine, baseUrl, dataSource);
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl,
                cloud.jengu.dbo.core.process.Steps steps) {
            return served.store(engine, baseUrl, steps);
        }

        @Override
        public FhirTerminology terminology(ObjectStore engine, DataSource dataSource) {
            // REQ-DBO-TERM-EVERY-TENANT-ANSWERS: the native form is this
            // version's, and reassembling it is not something definitions
            // describe — so this half has not moved.
            return new R5Terminology(engine, personality, new TerminologyStore(dataSource));
        }

        @Override
        public PortableRendering portableRendering() {
            return served.portableRendering();
        }
    }
}
