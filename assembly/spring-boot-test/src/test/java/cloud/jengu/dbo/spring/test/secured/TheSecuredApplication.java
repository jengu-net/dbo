package cloud.jengu.dbo.spring.test.secured;

import cloud.jengu.dbo.auth.Subject;
import cloud.jengu.dbo.auth.UserClaims;
import cloud.jengu.dbo.spring.server.DboAuthentication;
import cloud.jengu.dbo.spring.server.DboBearerTokens;
import cloud.jengu.dbo.spring.server.DboRequestTenant;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * An application with an API of its own, secured the way an application on
 * these assemblies secures one: Spring Security's resource server, with the
 * store answering whose token it is.
 */
@SpringBootApplication
public class TheSecuredApplication {

    /** Its API is under {@code /api/<tenant>/}, so that is where it reads the tenant. */
    @Bean
    DboRequestTenant theTenantInThePath() {
        return request -> {
            String[] path = request.getRequestURI().split("/");
            return path.length > 2 && "api".equals(path[1])
                    ? Optional.of(path[2]) : Optional.empty();
        };
    }

    @Bean
    SecurityFilterChain theApi(HttpSecurity http, DboBearerTokens tokens) throws Exception {
        return http.securityMatcher("/api/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.authenticationManagerResolver(tokens))
                .build();
    }

    /**
     * What this application adds to a person's claims, and the ways adding
     * can go wrong, each answering one client so a test can ask for it.
     */
    @Bean
    UserClaims whatThisApplicationAdds() {
        return subject -> switch (subject.clientId()) {
            case "breaks" -> throw new IllegalStateException("the directory this reads is down");
            case "oversteps" -> Map.of("sub", "somebody-else");
            case "overflows" -> Map.of("notes", "x".repeat(8_192));
            default -> Map.of(
                    "practitioners", subject.practitioners().size(),
                    "held_at", subject.practitioners().stream()
                            .flatMap(practitioner -> practitioner.roles().stream())
                            .flatMap(role -> role.organization().stream())
                            .map(Subject.Organization::json)
                            .map(json -> json.replaceAll("(?s).*\"name\"\\s*:\\s*\"([^\"]*)\".*",
                                    "$1"))
                            .toList());
        };
    }

    @RestController
    static class WhoIsAsking {

        /** Says what the store decided about the caller. */
        @GetMapping("/api/{tenant}/me")
        String me(@PathVariable("tenant") String tenant) {
            DboAuthentication asking =
                    (DboAuthentication) SecurityContextHolder.getContext().getAuthentication();
            return "tenant=" + asking.tenant() + " audience=" + asking.context().audience()
                    + " authorities=" + asking.getAuthorities();
        }
    }
}
