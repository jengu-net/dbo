package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.IndexSpec;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.ValueKind;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PortableRendering;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTerminology;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.SearchParameter;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * One version of FHIR, served from carried definitions through one
 * implementation (#58).
 *
 * <p>What a bring-up asks for is here; what the engine asks for is on
 * {@link #face()}. Neither knows which version this is: the code, the payload
 * version, the types' search behaviour and the shapes to validate against all
 * come from the package the face carries.
 */
public class ElementFhirVersion implements FhirVersion {

    private final String code;
    private final String payloadVersion;

    /**
     * The definitions are loaded when something asks this version to do
     * anything, not when it announces itself.
     *
     * <p>A version's package is tens of megabytes parsed into a context, and a
     * container installs every face it has whether or not a tenant declares
     * one. Loading at registration made starting the bundle cost the memory of
     * every version it carries, for a node that might serve none of them — it
     * arrives as an OutOfMemoryError inside a bundle activator, which is about
     * as far from its cause as a failure gets.
     */
    protected ElementFhirVersion(String code) {
        this(code, null);
    }

    /**
     * @param payloadVersion the coordinate payloads are stored under, when it
     *                       is not the one the definitions declare. A released
     *                       version has a stable coordinate the store and its
     *                       converters already agree on — R4 is {@code 4.0},
     *                       not {@code 4.0.1} — and changing it would mean
     *                       stored rows and converter pairs disagreeing about
     *                       what a payload is. A version at ballot has no such
     *                       agreement and is recorded exactly
     *                       (REQ-DBO-VER-BALLOT-RECORDED-PER-VERSION).
     */
    protected ElementFhirVersion(String code, String payloadVersion) {
        this.code = code;
        this.payloadVersion = payloadVersion;
    }

    private ElementVersion version() {
        return ElementVersion.of(code);
    }

    /** One version, by the code its carried definitions are indexed under. */
    public static ElementFhirVersion serving(String code) {
        return new ElementFhirVersion(code, null);
    }

    /** The same, for a version whose stored coordinate is not the definitions'. */
    public static ElementFhirVersion serving(String code, String payloadVersion) {
        return new ElementFhirVersion(code, payloadVersion);
    }

    @Override
    public String code() {
        return code;
    }

    /**
     * The storage domain, which is the version's own code: two versions in one
     * tenant are two domains, which is what R6 asks for and what keeps an R4
     * object out of an R6 search.
     */
    @Override
    public String domain() {
        return code;
    }

    @Override
    public String payloadVersion() {
        return payloadVersion != null ? payloadVersion : version().payloadVersion();
    }

    @Override
    public DomainFace face() {
        return version().face();
    }

    @Override
    public ForTypes forTypes(List<FhirTypeConfig> types) {
        return new Tenant(version(), List.copyOf(types), payloadVersion());
    }

    private record Tenant(ElementVersion version, List<FhirTypeConfig> types,
            String payloadVersion) implements ForTypes {

        @Override
        public List<TypeRegistration> registrations() {
            return registrations(version.code());
        }

        @Override
        public List<TypeRegistration> registrations(String domain) {
            List<TypeRegistration> out = new ArrayList<>();
            for (FhirTypeConfig type : types) {
                out.add(new TypeRegistration(type.typeName(), domain, type.identityClass(),
                        type.identitySystems(), type.handling(),
                        version.extractor(type.typeName(),
                                type.identityClass() == cloud.jengu.dbo.core.api.IdentityClass.CANONICAL),
                        indexes(type.typeName()),
                        payloadVersion));
            }
            return out;
        }

        /**
         * Declared indexes for the sort paths a clinical search actually uses:
         * the date parameters this version defines for the type
         * (REQ-DBO-SRCH-DECLARED-INDEXES).
         */
        private List<IndexSpec> indexes(String typeName) {
            List<IndexSpec> specs = new ArrayList<>();
            for (SearchParameter parameter : version.parametersFor(typeName)) {
                if (parameter.getType() == Enumerations.SearchParamType.DATE) {
                    specs.add(new IndexSpec(parameter.getCode().replace('-', '_'),
                            ValueKind.DATE));
                }
            }
            return specs;
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl) {
            return new ElementStore(engine, version, types, baseUrl);
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl,
                javax.sql.DataSource dataSource) {
            cloud.jengu.dbo.terminology.TerminologyStore terminology =
                    new cloud.jengu.dbo.terminology.TerminologyStore(dataSource);
            // the carried baseline becomes tenant data, once — see the class
            long baselineAt = System.currentTimeMillis();
            TerminologyBaseline.ensure(terminology, version.code());
            long baselineMillis = System.currentTimeMillis() - baselineAt;
            long profilesAt = System.currentTimeMillis();
            ElementStore store = new ElementStore(engine, version, types, baseUrl,
                    cloud.jengu.dbo.core.process.Steps.of(), new StoreTerms(terminology));
            // Said out loud because a first boot's cost was a bound inferred
            // from a task's wall clock, and a bound is not a measurement (#93).
            org.slf4j.LoggerFactory.getLogger("dbo.face").info(
                    "face bring-up cost: version={} terminologyBaseline={}ms profiles={}ms",
                    version.code(), baselineMillis,
                    System.currentTimeMillis() - profilesAt);
            return store;
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl,
                cloud.jengu.dbo.core.process.Steps steps) {
            return new ElementStore(engine, version, types, baseUrl, steps);
        }

        @Override
        public FhirTerminology terminology(ObjectStore engine, DataSource dataSource) {
            return new ElementTerminology(engine, version, types,
                    new cloud.jengu.dbo.terminology.TerminologyStore(dataSource));
        }

        @Override
        public PortableRendering portableRendering() {
            // One implementation, declared on the face and reached here. The
            // method stays because FhirVersion's callers use it; what changed
            // is that the obligation is now placed rather than duplicated
            // wherever somebody needs it (#106).
            return version.face().require(PortableRendering.class);
        }
    }
}
