package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.TypeRegistration;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A clinician's edge PIN is a credential, not a field on a
 * configured record.
 *
 * <p>It used to live as an extension on a {@code Practitioner} projected from
 * the configuration repository, where the only thing protecting it was that
 * the projection never updated existing practitioners. Reconciling apply
 * removes that accident and a routine config commit deletes the credential —
 * found by a clinician at a bedside, caused by a commit that looked unrelated.
 *
 * <p>FHIR was never the right home either way: the standard deliberately
 * layers authentication outside the resource model, so the extension stood
 * where no FHIR shape was ever going to exist.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgePinIsACredentialIT {

    static TenantAuthority authority;
    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("EdgePinIsACredentialIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE edge_pin");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "edge_pin");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        store = new PgObjectStore(ds, IdentityModel.registrations());
        authority = new TenantAuthority(store, "http://127.0.0.1:1/oidc", new KeyProtector(kek));
        authority.ensureLocalCredential("albus@hogwarts.scot", "test1234", "prac-1");
    }

    @Test
    @Timeout(300)
    @DisplayName("a PIN set at the bench is verified there, and is not on any clinical record")
    void aPinIsACredential() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");

        assertTrue(authority.verifyFactor("albus@hogwarts.scot", "pin", "4815"));
        assertFalse(authority.verifyFactor("albus@hogwarts.scot", "pin", "1234"));
    }

    @Test
    @Timeout(300)
    @DisplayName("setting a password leaves the PIN, and setting a PIN leaves the password")
    void factorsDoNotOverwriteEachOther() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");

        // the config-driven path runs again, as bootstrap does on every start
        authority.ensureLocalCredential("albus@hogwarts.scot", "test1234", "prac-1");

        assertTrue(authority.verifyFactor("albus@hogwarts.scot", "pin", "4815"),
                "re-provisioning a login must not take away the PIN the clinician set — that is "
                        + "the whole-record overwrite this issue exists to remove, one level down");

        // and the reverse: a new PIN must leave the password byte-identical.
        // Comparing the stored hash rather than its shape keeps this true
        // whichever algorithm the hash happens to use.
        String before = secretHash();
        authority.setFactor("albus@hogwarts.scot", "pin", "1623");
        assertEquals(before, secretHash(),
                "setting a PIN re-wrote the password hash — the two factors must be independent");
        assertTrue(authority.verifyFactor("albus@hogwarts.scot", "pin", "1623"));
    }

    private static String secretHash() {
        String stored = new String(store.getByIdentifier("LocalCredential",
                        List.of(new Identifier(IdentityModel.LOGIN_SYSTEM, "albus@hogwarts.scot")))
                .stream().findFirst().orElseThrow().payload(), StandardCharsets.UTF_8);
        int at = stored.indexOf("\"secretHash\":\"") + "\"secretHash\":\"".length();
        return stored.substring(at, stored.indexOf('"', at));
    }

    @Test
    @Timeout(300)
    @DisplayName("factors are kinds — a one-time code sits beside the PIN, not over it")
    void factorsAreKindsNotFields() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");
        authority.setFactor("albus@hogwarts.scot", "otp", "162342");

        assertTrue(authority.verifyFactor("albus@hogwarts.scot", "pin", "4815"),
                "adding a second kind must not displace the first — naming the field after the "
                        + "first case we met would have made every later one an exception");
        assertTrue(authority.verifyFactor("albus@hogwarts.scot", "otp", "162342"));
        assertFalse(authority.verifyFactor("albus@hogwarts.scot", "otp", "4815"),
                "and a secret proves only the kind it was set for");
    }

    @Test
    @Timeout(300)
    @DisplayName("a PIN cannot conjure a login that nobody has a password for")
    void aPinDoesNotCreateALogin() {
        assertThrows(IllegalArgumentException.class,
                () -> authority.setFactor("nobody@hogwarts.scot", "pin", "0000"));
    }

    @Test
    @Timeout(300)
    @DisplayName("a bench gets verifiers for offline sign-in, and only hashes")
    void offlineVerifiersAreDistributedAsHashesOnly() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");

        var distributed = authority.factorsFor("pin");

        assertEquals(1, distributed.size());
        assertEquals("albus@hogwarts.scot", distributed.get(0).getKey());
        assertFalse(distributed.get(0).getValue().contains("4815"),
                "what reaches a bench must verify a PIN and be unable to produce one");
        assertTrue(cloud.jengu.dbo.auth.SecretHashProbe.verifies("4815",
                        distributed.get(0).getValue()),
                "and it must actually verify, or an offline sign-in fails at the bedside");
    }

    @Test
    @Timeout(300)
    @DisplayName("a login with no PIN is not distributed at all")
    void aLoginWithoutAPinIsNotShipped() {
        authority.ensureLocalCredential("nopin@hogwarts.scot", "test1234", "prac-2");

        assertTrue(authority.factorsFor("pin").stream()
                        .noneMatch(e -> e.getKey().equals("nopin@hogwarts.scot")),
                "a bench holds verifiers for people who can sign in there, and nobody else");
    }

    @Test
    @Timeout(300)
    @DisplayName("the credential is store-authored — it rides a backup and never an export")
    void theCredentialIsClassifiedAsOne() {
        TypeRegistration credential = IdentityModel.registrations().stream()
                .filter(t -> t.typeName().equals("LocalCredential"))
                .findFirst().orElseThrow();

        Handling handling = credential.handling();
        assertTrue(handling.travelsInBackup(),
                "a backup without credentials cannot authenticate its own tenants");
        assertFalse(handling.travelsInPortableExport(),
                "and an export carrying them hands somebody a way in");
        assertFalse(handling.isWritableBy(Handling.Authority.CONFIG_LANE),
                "configuration does not own a credential somebody set for themselves");
    }
}
