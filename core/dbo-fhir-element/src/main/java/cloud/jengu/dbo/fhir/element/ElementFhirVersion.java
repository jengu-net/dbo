package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.IndexSpec;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.ValueKind;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PortableRendering;
import cloud.jengu.dbo.fhir.common.FaceDefinitions;
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
 * implementation.
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

    /**
     * One tenant's declaration of types, and the one place its registrations
     * and its store meet. Registrations are asked for first — the engine is
     * built over them — and the store after, over the engine; the extractors
     * inside the registrations read documents through whichever view the
     * store built, which they learn here once it exists.
     */
    private static final class Tenant implements ForTypes {
        private final ElementVersion version;
        private final List<FhirTypeConfig> types;
        private final String payloadVersion;
        // Weak, and deliberately: a registration lives inside the engine, and
        // an engine outlives the facade over it in every test that keeps one
        // in a static field. Held strongly here, the facade — its view, its
        // validators, their caches — was kept for the life of the process by
        // every engine that ever had one, and the suite's live set grew by a
        // tenant per class until the heap was all of it.
        private final java.util.concurrent.atomic.AtomicReference<
                java.lang.ref.WeakReference<ElementStore>> view = new java.util.concurrent.atomic.AtomicReference<>();

        Tenant(ElementVersion version, List<FhirTypeConfig> types, String payloadVersion) {
            this.version = version;
            this.types = types;
            this.payloadVersion = payloadVersion;
        }

        @Override
        public List<TypeRegistration> registrations() {
            return registrations(version.code());
        }

        /**
         * Refuses a type declared as one this face defines, which it does not.
         *
         * <p>Saying a face has no definition for a type is the declaration's
         * job — {@code none} means it is held as itself, stored verbatim and
         * validated against nothing — and the face never infers it, because a
         * face that noticed it had no definition for a name and quietly
         * switched would turn every typo into an opaque type.
         *
         * <p>What nothing answered was the case in between. A name this face
         * does not know, declared as though it did, came up serving: the
         * capability statement advertised it, a search of it answered an empty
         * bundle, and only a write refused — blaming the caller's body for
         * being unparseable rather than the declaration for naming a type that
         * does not exist. Three answers that each read reasonably alone.
         *
         * <p><b>Asked of the definitions this face carries, not of the
         * toolchain.</b> Building the toolchain's context would answer as
         * well and a bring-up is not allowed to cost one — there is a test
         * that says so by name. The carried definitions are already on disk
         * and already indexed, so this is a set lookup.
         *
         * <p>Not asked of a type's search parameters, which was the first
         * idea and is wrong: {@code Binary} is a resource this face defines
         * and declares no search parameters, so it is indistinguishable that
         * way from a name nobody has ever heard of.
         */
        private void refuseWhatThisFaceHasNoDefinitionFor() {
            List<String> unknown = new ArrayList<>();
            for (FhirTypeConfig type : types) {
                if (type.definition() != FhirTypeConfig.Definition.NONE
                        && !version.defines(type.typeName())) {
                    unknown.add(type.typeName());
                }
            }
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("face " + version.code()
                        + " has no definition for " + unknown + ", and they are declared as "
                        + "types it defines. Correct the name, or declare the definition as "
                        + "'none' to have the type held as itself: stored verbatim, indexed "
                        + "by the identity it declares, and validated against nothing");
            }
        }

        /**
         * The extractor a type asked for.
         *
         * <p>A type may say its envelope is computed where the bytes are; the
         * face still supplies the in-process one, because that is what the
         * database's answer is compared against and what a store with no such
         * function falls back to. Wired HERE and not only on the personalities:
         * this is what a served tenant's registrations are built from, and a
         * version that honoured the declaration somewhere else would be a
         * feature no tenant could reach.
         */
        private cloud.jengu.dbo.core.api.EnvelopeExtractor asDeclared(FhirTypeConfig type,
                cloud.jengu.dbo.core.api.EnvelopeExtractor inProcess) {
            if (type.extraction() != FhirTypeConfig.Extraction.IN_THE_DATABASE) {
                return inProcess;
            }
            return new cloud.jengu.dbo.core.api.DatabaseExtractor() {

                @Override
                public String functionName() {
                    return "dbo.envelope_parts";
                }

                @Override
                public cloud.jengu.dbo.core.api.Envelope extract(String typeName,
                        byte[] payload) {
                    return inProcess.extract(typeName, payload);
                }
            };
        }

        @Override
        public List<TypeRegistration> registrations(String domain) {
            refuseWhatThisFaceHasNoDefinitionFor();
            List<TypeRegistration> out = new ArrayList<>();
            for (FhirTypeConfig type : types) {
                // A type the face has no definition for never reaches the
                // toolchain: the ordinary extractor's first act is to parse,
                // and parsing is exactly what refuses a resource type the
                // specification does not have.
                cloud.jengu.dbo.core.api.EnvelopeExtractor inProcess =
                        type.definition() == FhirTypeConfig.Definition.NONE
                                ? (typeName, payload) -> OpaqueEnvelopes.extract(typeName,
                                        type.identitySystems(), payload)
                                : version.extractor(type.typeName(),
                                        type.identityClass()
                                                == cloud.jengu.dbo.core.api.IdentityClass.CANONICAL,
                                        List.of(), this::through, this::compiled);
                out.add(new TypeRegistration(type.typeName(), domain, type.identityClass(),
                        type.identitySystems(), type.handling(),
                        asDeclared(type, inProcess),
                        indexes(type.typeName()),
                        payloadVersion));
            }
            // The face's definitions are a domain of their own, wherever this
            // version's records are told to live.
            return FaceDefinitions.placed(out, domain);
        }

        /**
         * The parameters the cut compiled, once there is a store to ask and
         * it was asked to read them. Empty until then, which is the toolchain
         * path — the same answer a registration made before a store exists
         * has always had.
         */
        private java.util.List<cloud.jengu.dbo.fhir.index.DefinitionRows.Parameter> compiled() {
            java.lang.ref.WeakReference<ElementStore> held = view.get();
            ElementStore store = held == null ? null : held.get();
            return store == null ? java.util.List.of() : store.compiledParameters();
        }

        /** The store's view once there is a store; the version's own payloads until then. */
        private ElementPayloads through() {
            java.lang.ref.WeakReference<ElementStore> held = view.get();
            ElementStore store = held == null ? null : held.get();
            return store != null ? store.elementPayloads() : version.payloads();
        }

        private ElementStore viewed(ElementStore store) {
            view.set(new java.lang.ref.WeakReference<>(store));
            return store;
        }

        /**
         * Declared indexes for the sort paths a clinical search actually uses:
         * the date parameters this version defines for the type
         * (REQ-DBO-SRCH-DECLARED-INDEXES).
         *
         * <p>What a tenant later authors declares its index by the same rule,
         * which is why the rule lives on the version rather than here: a
         * bring-up registration and a re-registration that must agree about
         * what an index is should not be two derivations of it.
         */
        private List<IndexSpec> indexes(String typeName) {
            return ElementVersion.indexesFor(version.parametersFor(typeName));
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl) {
            return viewed(new ElementStore(engine, version, types, baseUrl));
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl,
                javax.sql.DataSource dataSource) {
            return store(engine, baseUrl, dataSource, false);
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl,
                javax.sql.DataSource dataSource, boolean versionHeldAsRecords) {
            cloud.jengu.dbo.terminology.TerminologyStore terminology =
                    new cloud.jengu.dbo.terminology.TerminologyStore(dataSource);
            cloud.jengu.dbo.definitions.DefinitionStore definitions =
                    new cloud.jengu.dbo.definitions.DefinitionStore(dataSource);
            // The functions this release answers with, put in place by the
            // release: they read the rows above, so they are installed
            // beside them and by nothing else (a function arriving through a
            // chain would be a way to run code on a tenant by writing to a
            // feed). A no-op when the release's SQL is already what is here.
            String functions = cloud.jengu.dbo.definitions.FaceFunctions.install(dataSource);
            // the carried baseline becomes tenant data, once — see the class
            long baselineAt = System.currentTimeMillis();
            if (!versionHeldAsRecords) {
                // A tenant on a face takes the version's terminology from its
                // records — a root loads it from the packages once, a
                // subscriber takes it from the root — and is the one place
                // the carried packages are not read.
                TerminologyBaseline.ensure(terminology, version.code());
            }
            long baselineMillis = System.currentTimeMillis() - baselineAt;
            long profilesAt = System.currentTimeMillis();
            ElementStore store = viewed(new ElementStore(engine, version, types, baseUrl,
                    cloud.jengu.dbo.core.process.Steps.of(), new StoreTerms(terminology),
                    definitions, dataSource));
            // What the tenant already holds and has never been taken apart:
            // an upgrade to a store that expands definitions, or a rebuild.
            // A no-op once the rows are there, which is every bring-up after
            // the first.
            long expandedAt = System.currentTimeMillis();
            int expanded = store.expandDefinitionsHeld();
            // Said out loud because a first boot's cost was a bound inferred
            // from a task's wall clock, and a bound is not a measurement.
            org.slf4j.LoggerFactory.getLogger("dbo.face").info(
                    "face bring-up cost: version={} terminologyBaseline={}ms profiles={}ms"
                    + " definitionsExpanded={} in {}ms functions={}",
                    version.code(), baselineMillis,
                    System.currentTimeMillis() - profilesAt, expanded,
                    System.currentTimeMillis() - expandedAt, functions);
            return store;
        }

        @Override
        public FhirStoreFacade store(ObjectStore engine, String baseUrl,
                cloud.jengu.dbo.core.process.Steps steps) {
            return viewed(new ElementStore(engine, version, types, baseUrl, steps));
        }

        @Override
        public FhirTerminology terminology(ObjectStore engine, DataSource dataSource) {
            return new ElementTerminology(engine, version, types,
                    new cloud.jengu.dbo.terminology.TerminologyStore(dataSource));
        }

        @Override
        public PortableRendering portableRendering() {
            // Through the tenant's view once it has a store, like every other
            // read of its records: an export rendered through the version's
            // carried context was the last thing that built one for a tenant
            // holding its version as records.
            return (payload, id, versionId) -> new String(
                    ElementAncestors.rendered(payload, id, versionId),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
