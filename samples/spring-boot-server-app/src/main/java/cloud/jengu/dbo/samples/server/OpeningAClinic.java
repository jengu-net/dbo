package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.spring.server.DboTenantListener;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.tenant.api.TenantFacts;
import cloud.jengu.dbo.tenant.api.TenantLifecycleListener;
import cloud.jengu.dbo.tenant.api.TenantPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What the clinic's application declares in a clinic as it opens.
 *
 * <p>A clinic with a staff directory has people who sign in, and what each of
 * them may do is a grant the clinic holds rather than code this application
 * ships: a role, by its code, and the scopes it carries. The identity
 * provider says who works here; which role they act in is a record in the
 * clinic's own store. So the roles are declared here, once the clinic is
 * serving, and a person holding one is reached by it at their next token.
 *
 * <p>And the application itself is a client of each such clinic, with the
 * narrowest scope its screens need. The clinic's own issuer mints its token
 * and checks it without asking anybody.
 *
 * <p><b>Declared again at every start, and that is the ordinary case.</b>
 * Configuration arrives from wherever the clinic keeps it, so declaring a
 * grant it already holds changes nothing; a bring-up that refused one would
 * make every redeploy a migration.
 */
@Component
@DboTenantListener(point = TenantPoint.SERVING, target = "(dbo.tenant.hasScim=true)")
public final class OpeningAClinic implements TenantLifecycleListener {

    /** The role a clinician acts in, and what it reaches. */
    public static final String CLINICIAN = "clinician";

    static final List<String> A_CLINICIANS_REACH =
            List.of("system/Patient.read", "system/Patient.write");

    /** This application, as a client of each clinic. */
    public static final String APPLICATION = "sample-clinic-application";

    /** What its screens read, and nothing they do not. */
    static final List<String> WHAT_THE_SCREENS_READ = List.of("system/Patient.read");

    private final DboTenants tenants;
    private final String secret;

    OpeningAClinic(DboTenants tenants,
            @Value("${clinic.application.secret}") String secret) {
        this.tenants = tenants;
        this.secret = secret;
    }

    @Override
    public void reached(TenantPoint point, TenantFacts tenant) {
        declare(tenant.code());
    }

    /**
     * Declares the clinic's roles and this application's client in a tenant.
     *
     * @return false where the tenant has no authority to declare them with
     */
    public boolean declare(String tenant) {
        TenantAuthority authority = tenants.authority(tenant).orElse(null);
        if (authority == null) {
            return false;
        }
        authority.ensureRoleGrant(CLINICIAN, A_CLINICIANS_REACH);
        authority.ensureClient(APPLICATION, secret, WHAT_THE_SCREENS_READ);
        return true;
    }
}
