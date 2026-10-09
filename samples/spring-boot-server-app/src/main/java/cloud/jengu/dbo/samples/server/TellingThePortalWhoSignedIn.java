package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.auth.Subject;
import cloud.jengu.dbo.auth.UserClaims;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * What the clinic's portal is told about the person who signed in, beyond
 * what the store says itself.
 *
 * <p>The portal shows the ward roles a clinician is acting in. It is told
 * them in the ID token rather than asking the store on every request, and
 * only the portal is: another application signing in at the same clinic has
 * no use for them. Everything here is read from what the authority already
 * loaded, so adding it costs no query.
 */
@Component
public final class TellingThePortalWhoSignedIn implements UserClaims {

    /** The portal's client ids end so; every other client is told nothing extra. */
    public static final String PORTAL = "-portal";

    @Override
    public Map<String, Object> contribute(Subject subject) {
        if (!subject.clientId().endsWith(PORTAL)) {
            return Map.of();
        }
        List<String> acting = subject.practitioners().stream()
                .flatMap(practitioner -> practitioner.roles().stream())
                .filter(Subject.Role::active)
                .flatMap(role -> role.codes().stream())
                .distinct().toList();
        return Map.of("ward_roles", acting);
    }
}
