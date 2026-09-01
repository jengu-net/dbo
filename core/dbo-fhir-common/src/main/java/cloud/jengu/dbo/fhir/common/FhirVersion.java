package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PortableRendering;

import javax.sql.DataSource;
import java.util.List;

/**
 * One FHIR version, as the tenant runtime needs it (R6).
 *
 * <p>{@link DomainFace} is what the <b>engine</b> asks a face for — the
 * capabilities it delegates, looked up by type. This is the other caller: what
 * the <b>runtime</b> asks a version for when a tenant declaring it comes up.
 * Without it, resolving the face still leaves the wiring branching on a string,
 * because the branches do not choose a face — they choose a store, a
 * terminology surface, a storage domain and a payload version.
 *
 * <p>Registered as a service under its {@link #code()}, so the set of versions
 * is what is installed rather than what a validator was taught to accept: a
 * tenant declaring a version nothing provides is refused because nothing
 * provides it. Adding one is a bundle, not an edit to the wiring.
 *
 * <p>Split in two halves for the reason R6 asks: what a version knows is built
 * once for the node, and what a tenant declares — its types — is per tenant.
 * A node serving twenty tenants on one version holds one of the first and
 * twenty of the second.
 */
public interface FhirVersion {

    /** {@code r4}, {@code r5}, {@code r6} — what a tenant spec declares. */
    String code();

    /** The storage domain this version's objects live in. */
    String domain();

    /**
     * The version a payload is stored under, spelled exactly — a ballot by its
     * full name (REQ-DBO-VER-BALLOT-RECORDED-PER-VERSION), never the release it
     * anticipates.
     */
    String payloadVersion();

    /** What the engine requires from this version, capability by capability. */
    DomainFace face();

    /** This version as one tenant's declared types make it. */
    ForTypes forTypes(List<FhirTypeConfig> types);

    /**
     * The per-tenant half. Everything a bring-up builds, asked for by what it
     * is rather than by which class implements it.
     */
    interface ForTypes {

        /** Type registrations over this version's own domain. */
        List<TypeRegistration> registrations();

        /** The same over an explicit domain — the re-binding seam for version transitions. */
        List<TypeRegistration> registrations(String domain);

        /** The outward facade an HTTP surface serves through. */
        FhirStoreFacade store(ObjectStore engine, String baseUrl);

        /**
         * The same, knowing the tenant's database — which is where the
         * tenant's OWN validation truth lives: its terminology today, its
         * structure definitions next. The default ignores it, so a
         * face whose validation reads nothing but carried definitions is
         * unchanged; a face that validates against current data overrides.
         */
        default FhirStoreFacade store(ObjectStore engine, String baseUrl,
                javax.sql.DataSource dataSource) {
            return store(engine, baseUrl);
        }

        /**
         * The same, knowing the steps this container has.
         *
         * <p>Passed in rather than discovered: a face inside a bundle that
         * scanned the classpath for catalogues would find whatever happened to
         * be on it and fail to load classes it cannot see — the same reason
         * versions are handed to the runtime rather than looked up by it.
         */
        default FhirStoreFacade store(ObjectStore engine, String baseUrl,
                cloud.jengu.dbo.core.process.Steps steps) {
            return store(engine, baseUrl);
        }

        /**
         * The tenant's own terminology, built per tenant because the native
         * form is (REQ-DBO-TERM-EVERY-TENANT-ANSWERS). The data source is the
         * tenant's; what is kept in it is this version's business.
         */
        FhirTerminology terminology(ObjectStore engine, DataSource dataSource);

        /** The engine's own facts said in this version's words, for export. */
        PortableRendering portableRendering();
    }
}
