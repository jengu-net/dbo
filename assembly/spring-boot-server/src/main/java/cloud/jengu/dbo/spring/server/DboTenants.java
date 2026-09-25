package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.asking.Questions;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.embedded.EmbeddedRuntime;

import java.util.List;
import java.util.Optional;

/**
 * What this application's tenants are, and what each will answer.
 *
 * <p>The vocabulary an application reads the deployment through. Every method
 * asks the container as it is asked: a tenant that went away a minute ago is
 * gone from the answer, and one that came up a minute ago is in it. Nothing
 * here is cached, because a handle to a tenant that has been retracted is
 * worse than no handle at all — it works until it does not, and then it works
 * wrongly.
 *
 * <p><b>This reads. Declaring a tenant is a different act</b>, with a
 * different right behind it, and the runtime already keeps the two apart.
 */
public final class DboTenants {

    private final EmbeddedRuntime runtime;

    public DboTenants(EmbeddedRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * The tenants serving right now, by code.
     *
     * <p>Read off the registry rather than kept: every per-tenant service is
     * published with its tenant's code on it, and a list held beside that
     * would be right until the next tenant came up.
     */
    public List<String> serving() {
        return runtime.lookup().valuesOf(ObjectStore.class, "tenant");
    }

    /** Whether this one is up and answering. */
    public boolean isServing(String tenant) {
        return store(tenant).isPresent();
    }

    /** The engine behind a tenant, for code that reaches past the face. */
    public Optional<ObjectStore> store(String tenant) {
        return runtime.lookup().one(ObjectStore.class, forTenant(tenant));
    }

    /** A tenant's records, in the words its face speaks. */
    public Optional<FhirStoreFacade> records(String tenant) {
        return runtime.lookup().one(FhirStoreFacade.class, forTenant(tenant));
    }

    /** What happened at a tenant, as a feed. */
    public Optional<ChangeFeed> changes(String tenant) {
        return runtime.lookup().one(ChangeFeed.class, forTenant(tenant));
    }

    /**
     * The questions a product asks a tenant about its own work.
     *
     * <p>{@code Questions} rather than the class behind it, which is final:
     * an application injecting a proxy needs an interface, and the store's
     * own vocabulary is the interface it already has.
     */
    public Optional<Questions> asking(String tenant) {
        return runtime.lookup().one(Questions.class, forTenant(tenant))
                .or(() -> runtime.lookup().one(cloud.jengu.dbo.asking.Asking.class,
                        forTenant(tenant)).map(asking -> asking));
    }

    /**
     * The authority that says who is asking, for a tenant that has one.
     *
     * <p>Only this tenant's own keys verify its own tokens, so a cross-tenant
     * credential is indistinguishable from garbage. That property is what an
     * application inherits by being handed this rather than verifying
     * somebody else's word about these records.
     */
    public Optional<TenantAuthority> authority(String tenant) {
        return runtime.lookup().one(TenantAuthority.class, forTenant(tenant));
    }

    private static String forTenant(String tenant) {
        return "(tenant=" + tenant + ")";
    }
}
