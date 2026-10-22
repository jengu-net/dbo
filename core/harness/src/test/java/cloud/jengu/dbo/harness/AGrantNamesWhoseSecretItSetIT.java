package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A consumer that keeps a subject's secret beside the subject has to know
 * whose secret a redeemed grant set: the page holding the grant also names a
 * subject, and a holder of one grant could name somebody else beside it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AGrantNamesWhoseSecretItSetIT {

    TenantAuthority authority;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("AGrantNamesWhoseSecretItSetIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE grant_names_its_subject");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "grant_names_its_subject");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        authority = new TenantAuthority(new PgObjectStore(ds, IdentityModel.registrations()),
                "http://127.0.0.1:1/oidc", new KeyProtector(kek));
        authority.ensureLocalCredential("minerva@hogwarts.scot", "unknown9-to-her", "prac-minerva");
        authority.ensureLocalCredential("severus@hogwarts.scot", "unknown9-to-him", "prac-severus");
    }

    @Test
    @DisplayName("a redeemed grant answers with the login it was minted for and the person whose it is, "
            + "a refused one with nothing")
    @Proving(DboPromises.AUTH_A_REDEEMED_GRANT_NAMES_ITS_SUBJECT_TO_ITS_CONSUMER)
    void aRedeemedGrantNamesItsSubject() {
        String hers = authority.mintSecretGrant("minerva@hogwarts.scot", Duration.ofMinutes(5));
        assertEquals(Optional.of(new TenantAuthority.RedeemedGrant("minerva@hogwarts.scot", "prac-minerva")),
                authority.redeemSecretGrantFor(hers, "tabby9cat"));
        assertEquals(Optional.empty(), authority.redeemSecretGrantFor(hers, "again9tabby"));
        assertEquals(Optional.empty(), authority.redeemSecretGrantFor("a-grant-nobody-minted", "tabby9cat"));
        assertEquals(Optional.empty(), authority.redeemSecretGrantFor(
                authority.mintSecretGrant("nobody@hogwarts.scot", Duration.ofMinutes(5)), "tabby9cat"));
        assertEquals(Optional.empty(), authority.redeemSecretGrantFor(
                authority.mintSecretGrant("severus@hogwarts.scot", Duration.ofMinutes(-1)), "potions9"));
    }
}
