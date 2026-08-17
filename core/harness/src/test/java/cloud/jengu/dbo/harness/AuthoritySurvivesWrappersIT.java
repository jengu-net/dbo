package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.pdi.PdiObjectStore;
import cloud.jengu.dbo.pdi.PdiSpec;
import cloud.jengu.dbo.pdi.PersonVault;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#41: a caller's declared authority survives every wrapper it passes.
 *
 * <p>Without this, the shields from jengu-platform#870 are only enforceable by
 * callers holding a concrete store. A replication lane holds an
 * {@link ObjectStore}, so it could not declare itself the source tenant at
 * all, and {@code READ_ONLY_HERE} would be a rule nobody could satisfy.
 *
 * <p>The failure mode is what makes it worth a test rather than a review: a
 * wrapper that drops the authority fails <b>toward permissive</b>. The caller
 * believes it declared something, the shield sees the least-privileged
 * default, and nothing announces the difference.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthoritySurvivesWrappersIT {

    private static final String DOMAIN = "wrapped";
    private static final EnvelopeExtractor PLAIN = (typeName, payload) -> new Envelope();

    private static final List<TypeRegistration> TYPES = List.of(
            new TypeRegistration("Vocabulary", DOMAIN, IdentityClass.INTERNAL, java.util.Set.of(),
                    Handling.replicated(), PLAIN, List.of()));

    static PGSimpleDataSource ds;
    static PgObjectStore direct;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("AuthoritySurvivesWrappersIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE wrapped_authority");
        }
        ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "wrapped_authority");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        direct = new PgObjectStore(ds, TYPES);
    }

    private static byte[] body() {
        return "{\"term\":\"published elsewhere\"}".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(300)
    @DisplayName("#41: the publishing lane can write replicated data through the interface")
    void thePublishingLaneCanDeclareItself() {
        ObjectStore asInterface = direct;

        assertNotNull(asInterface.put(PutRequest.create("Vocabulary", body()),
                Handling.Authority.SOURCE_TENANT).id(),
                "a lane holding an ObjectStore must be able to say it is the source tenant, or "
                        + "READ_ONLY_HERE is a rule nobody can satisfy");

        assertThrows(cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> asInterface.put(PutRequest.create("Vocabulary", body()),
                        Handling.Authority.TENANT_USERS));
    }

    @Test
    @Timeout(300)
    @DisplayName("#41: the authority survives the vault wrapper rather than being dropped")
    void theAuthoritySurvivesThePdiWrapper() {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        ObjectStore wrapped = new PdiObjectStore(direct, new PersonVault(ds, kek),
                PdiSpec.fhir());

        assertNotNull(wrapped.put(PutRequest.create("Vocabulary", body()),
                Handling.Authority.SOURCE_TENANT).id(),
                "the declaration must reach the store through the wrapper");

        cloud.jengu.dbo.core.api.HandlingRefusedException refused = assertThrows(
                cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> wrapped.put(PutRequest.create("Vocabulary", body()),
                        Handling.Authority.TENANT_USERS));

        assertTrue(refused.getMessage().contains("read-only-here"),
                "and a wrong one must still be refused — a wrapper that swallowed the authority "
                        + "would make this succeed, which is failing toward permissive: "
                        + refused.getMessage());
    }

    @Test
    @Timeout(300)
    @DisplayName("#41: the one-argument write still means the least-privileged caller")
    void theShortFormIsStillTenantUsers() {
        ObjectStore asInterface = direct;

        assertThrows(cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> asInterface.put(PutRequest.create("Vocabulary", body())),
                "not declaring an authority must not be a way to acquire one");
    }
}
