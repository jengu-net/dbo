package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Held;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.context.CanonicalResourceManager;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.PackageInformation;
import org.hl7.fhir.validation.ValidatorUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A version's definitions as one worker context per face and process,
 * built from the records a tenant holds rather than from the carried
 * packages, and shared by every tenant on that face.
 *
 * <p>Shared, because it cannot be small. The toolchain parses every type
 * definition the moment it is registered — a few hundred structures,
 * tens of megabytes — however lazily the rest is offered, so a context per
 * tenant was measured at eighty-six megabytes each against five for a copy
 * of a shared one. What is shared is the version's own: every definition
 * whose canonical is the specification's, identical in every tenant on the
 * face. What a tenant adds on top is its own and stays its own.
 *
 * <p>Built from records, because that is where the version is. The face
 * root loaded them from the packages once; a subscriber took them from the
 * root. The base is keyed by the version, and a definition is fetched by
 * its canonical url from whichever tenant on the face is still mounted —
 * so two roots of one version on one node share a base, and a tenant
 * taken down leaves the base able to answer from the others. Keyed by a
 * root's record ids it was one base per root, and one node's tests built
 * one per class until the heap ran out. The packages are read by a root's
 * loader, and by nothing here.
 *
 * <p>Held softly: a face nobody on this node is serving any more is a
 * hundred megabytes the collector may take.
 */
final class FaceBase {

    static final String VERSION_ROOT = "http://hl7.org/fhir/StructureDefinition/Resource";
    private static final String SPECIFICATION = "http://hl7.org/fhir/";
    /**
     * The types a context is given from records. SearchParameters are read
     * from the package by the version itself; CodeSystems are deliberately
     * absent, since a tenant holds a system's concepts natively and answers
     * for them itself — a context that knew the system as a shell would
     * answer "unknown code" for every code in it.
     */
    static final List<String> HELD_TYPES = List.of("StructureDefinition", "StructureMap", "ValueSet");

    private static final Map<String, SoftReference<FaceBase>> BY_VERSION = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong BUILDS =
            new java.util.concurrent.atomic.AtomicLong();

    /** How many bases this process has built — one per face version, if the sharing holds. */
    public static long builds() {
        return BUILDS.get();
    }
    // A lock, not a monitor: the build blocks on the database and runs the
    // toolchain for seconds, and a virtual thread holding a monitor through
    // that pins its carrier for the whole of it.
    private static final java.util.concurrent.locks.ReentrantLock BUILDING =
            new java.util.concurrent.locks.ReentrantLock();

    private final String fhirVersion;
    private final Rows context;
    // Weak: a tenant taken down is a shelf that is gone, and the base must
    // not be what keeps its engine — and everything behind it — alive.
    private final CopyOnWriteArrayList<java.lang.ref.WeakReference<ObjectStore>> shelves =
            new CopyOnWriteArrayList<>();

    private FaceBase(String fhirVersion, Rows context) {
        this.fhirVersion = fhirVersion;
        this.context = context;
    }

    /** Whether the store holds the version — its root definition, by canonical url. */
    static Optional<String> versionRootIn(ObjectStore store) {
        try {
            return store.getByIdentifier("StructureDefinition",
                            List.of(new Identifier(Identifier.CANONICAL_SYSTEM, VERSION_ROOT)))
                    .stream().map(StoredObject::id).findFirst();
        } catch (RuntimeException notRegistered) {
            return Optional.empty();
        }
    }

    /**
     * The base for the version this store holds, built from it if no tenant
     * on the face has built it yet — and the store joins the shelves the
     * base loads from either way.
     */
    static FaceBase of(String fhirVersion, ObjectStore store) {
        FaceBase held = held(fhirVersion);
        if (held == null) {
            BUILDING.lock();
            try {
                held = held(fhirVersion);
                if (held == null) {
                    held = build(fhirVersion, store);
                    BY_VERSION.put(fhirVersion, new SoftReference<>(held));
                }
            } finally {
                BUILDING.unlock();
            }
        }
        held.join(store);
        return held;
    }

    private void join(ObjectStore store) {
        for (java.lang.ref.WeakReference<ObjectStore> shelf : shelves) {
            if (shelf.get() == store) {
                return;
            }
        }
        shelves.removeIf(shelf -> shelf.get() == null);
        shelves.add(new java.lang.ref.WeakReference<>(store));
    }

    private static FaceBase held(String fhirVersion) {
        SoftReference<FaceBase> reference = BY_VERSION.get(fhirVersion);
        return reference == null ? null : reference.get();
    }

    private static FaceBase build(String fhirVersion, ObjectStore store) {
        try {
            BUILDS.incrementAndGet();
            Rows context = new Rows(fhirVersion);
            FaceBase base = new FaceBase(fhirVersion, context);
            base.join(store);
            long began = System.currentTimeMillis();
            int registered = 0;
            for (String type : HELD_TYPES) {
                for (Held held : inventoryOf(store, type)) {
                    String url = canonicalOf(held);
                    if (url != null && url.startsWith(SPECIFICATION)) {
                        context.register(new Proxy(type, held, url, base::load, context), context.packageInfo);
                        registered++;
                    }
                }
            }
            context.finishLoading();
            org.slf4j.LoggerFactory.getLogger("dbo.face").info(
                    "face base built from records: version={} definitions={} in {}ms",
                    fhirVersion, registered, System.currentTimeMillis() - began);
            return base;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot build the face base", e);
        }
    }

    SimpleWorkerContext context() {
        return context;
    }

    String fhirVersion() {
        return fhirVersion;
    }

    /** A definition's bytes, by canonical url, from whichever tenant on the face still holds it. */
    private byte[] load(String type, String url) {
        for (java.lang.ref.WeakReference<ObjectStore> reference : shelves) {
            ObjectStore shelf = reference.get();
            if (shelf == null) {
                continue;
            }
            try {
                List<StoredObject> stored = shelf.getByIdentifier(type,
                        List.of(new Identifier(Identifier.CANONICAL_SYSTEM, url)));
                if (!stored.isEmpty()) {
                    return stored.get(0).payload();
                }
            } catch (RuntimeException gone) {
                // a tenant taken down since it joined: the next one holds the same
            }
        }
        throw new FHIRException(type + " " + url + " is held by no tenant on this node any more");
    }

    static List<Held> inventoryOf(ObjectStore store, String type) {
        try {
            return store.inventory(type, List.of("version", "derivation"));
        } catch (RuntimeException notRegistered) {
            return List.of();
        }
    }

    static String canonicalOf(Held held) {
        return held.identifiers().stream()
                .filter(i -> Identifier.CANONICAL_SYSTEM.equals(i.system()))
                .map(Identifier::value).findFirst().orElse(null);
    }

    static String tokenOf(Held held, String path) {
        return held.firsts().get(path) instanceof EnvelopeValue.Token token ? token.code() : null;
    }

    /** How a definition's bytes are found once the context asks for it. */
    interface Loading {
        byte[] load(String type, String url);
    }

    /**
     * A worker context with nothing in it but what is registered, and the
     * version it describes — which a context built from nothing does not
     * know, and the element model asks.
     */
    static class Rows extends SimpleWorkerContext {
        final PackageInformation packageInfo;

        Rows(String fhirVersion) throws IOException {
            super(new SimpleWorkerContextBuilder().withAllowLoadingDuplicates(true).fromNothing());
            this.version = fhirVersion;
            this.packageInfo = theSpecificationsOwn(fhirVersion);
            setNoTerminologyServer(true);
            setCanRunWithoutTerminology(true);
            setExpansionParameters(new org.hl7.fhir.r5.model.Parameters());
        }

        /** A tenant's copy over a base: the base's entries shared, its own registered on top. */
        Rows(SimpleWorkerContext base, String fhirVersion) throws IOException {
            super(base);
            this.version = fhirVersion;
            // A tenant's own definitions are the tenant's. Only what is
            // registered on the BASE is the specification's, and only that
            // says so.
            this.packageInfo = new PackageInformation("records", "1", fhirVersion, new Date());
        }

        /**
         * What the definitions on the base came from, named as what they are.
         *
         * <p>The base holds the specification's own canonicals and nothing
         * else, and the toolchain asks where a definition came from before it
         * will believe a type exists: it accepts {@code Patient} as a type
         * when some definition it holds says its type is Patient AND came
         * from a package named like the core's. Registered as "records" — the
         * transport rather than the content — every profile a tenant wrote
         * was refused with "the type Patient is not legal because it is not
         * defined in the FHIR specification", on the tenants whose whole
         * design is to hold that specification as records.
         *
         * <p>What changed when definitions became records is how they travel,
         * not what they are, and this says so.
         */
        private static PackageInformation theSpecificationsOwn(String fhirVersion) {
            for (CarriedDefinitions.Carried carried : CarriedDefinitions.carried()) {
                if (carried.name().endsWith(".core") && carried.version().equals(fhirVersion)) {
                    return new PackageInformation(carried.name(), carried.version(),
                            fhirVersion, new Date());
                }
            }
            // Read from the index, which is metadata: no package is opened
            // here, and a face root is still the only thing that reads one.
            org.slf4j.LoggerFactory.getLogger("dbo.face").warn(
                    "no carried core package is version {}, so definitions held as records "
                    + "cannot say which specification they are — profiles written to a tenant "
                    + "on this face will be refused as naming types that do not exist",
                    fhirVersion);
            return new PackageInformation("records", "1", fhirVersion, new Date());
        }

        void register(Proxy proxy, PackageInformation packageInfo) {
            registerResourceFromPackage(proxy, packageInfo);
        }

        void finishLoading() {
            finishLoading(false);
        }
    }

    /**
     * One record, offered to the context by name and read only when asked
     * for — and returned exactly as read. Nothing is resolved from inside a
     * load: a load runs under the context's cache entry, which is empty
     * until the load returns, so a definition whose snapshot needs its base,
     * whose snapshot needs a type built on the first, re-enters the same
     * empty entry without end. The tooling package's logical models are
     * built that way. Snapshots are generated after registration, outside
     * any load, by the toolchain's own guarded generator.
     */
    static final class Proxy extends CanonicalResourceManager.CanonicalResourceProxy {
        private final String type;
        private final String url;
        private final Loading loading;
        private final SimpleWorkerContext context;

        Proxy(String type, Held held, String url, Loading loading, SimpleWorkerContext context) {
            // A structure is offered without its version on purpose. The
            // toolchain resolves structures by url, and a version stated on
            // the offer buys one thing only: a package hack that force-loads
            // every extension of the R4 core at registration to look for one
            // profile — four hundred parses, thirty megabytes, per process,
            // before anything asked for an extension. Value sets keep their
            // version, because bindings name them by it.
            super(type, held.id(), url,
                    "StructureDefinition".equals(type) ? null : tokenOf(held, "version"), null,
                    "StructureDefinition".equals(type) ? tokenOf(held, "derivation") : null, null);
            this.type = type;
            this.url = url;
            this.loading = loading;
            this.context = context;
        }

        @Override
        public CanonicalResource loadResource() throws FHIRException {
            byte[] payload = loading.load(type, url);
            try {
                return (CanonicalResource) new WithoutNarrative(
                        ValidatorUtils.loaderForVersion(context.getVersion()))
                        .loadResource(new ByteArrayInputStream(payload), true);
            } catch (IOException e) {
                throw new FHIRException("cannot read " + type + " " + url + ": " + e.getMessage(), e);
            }
        }
    }
}
