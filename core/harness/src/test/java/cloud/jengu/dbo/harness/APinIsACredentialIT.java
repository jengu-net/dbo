package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
 * A clinician's offline sign-in PIN is a credential, not a field on a
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
class APinIsACredentialIT {

    static TenantAuthority authority;
    static PgObjectStore store;
    /** A site serving a place of the same tenant, with a database and an authority of its own. */
    static TenantAuthority site;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("APinIsACredentialIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE pin_factor");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "pin_factor");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        store = new PgObjectStore(ds, IdentityModel.registrations());
        authority = new TenantAuthority(store, "http://127.0.0.1:1/oidc", new KeyProtector(kek));
        authority.ensureLocalCredential("albus@hogwarts.scot", "test1234", "prac-1");

        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE pin_factor_site");
        }
        PGSimpleDataSource siteDs = new PGSimpleDataSource();
        siteDs.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "pin_factor_site");
        siteDs.setUser(SharedPostgres.get().getUsername());
        siteDs.setPassword(SharedPostgres.get().getPassword());
        byte[] siteKek = new byte[32];
        new SecureRandom().nextBytes(siteKek);
        PgObjectStore siteStore = new PgObjectStore(siteDs, IdentityModel.registrations());
        site = new TenantAuthority(siteStore, "http://127.0.0.1:2/oidc", new KeyProtector(siteKek));

    }

    /** What the tenant hands its sites for this factor, as each site would take it. */
    private static void handedToTheSite(String amr) {
        for (TenantAuthority.FactorHolder holder : authority.holdersOf(amr)) {
            site.keepFactor(holder.login(), holder.personId(), amr, holder.hash());
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("a PIN set for a login is verified where it was set, and is not on any "
            + "clinical record")
    @Proving(DboPromises.AUTH_CREDENTIAL_FACTORS_BY_KIND)
    void aPinIsACredential() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");

        assertTrue(authority.verifyFactor("albus@hogwarts.scot", "pin", "4815"));
        assertFalse(authority.verifyFactor("albus@hogwarts.scot", "pin", "1234"));
    }

    @Test
    @Timeout(300)
    @DisplayName("setting a password leaves the PIN, and setting a PIN leaves the password")
    @Proving(DboPromises.AUTH_CREDENTIAL_FACTORS_BY_KIND)
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
    @Proving(DboPromises.AUTH_CREDENTIAL_FACTORS_BY_KIND)
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
    @DisplayName("a second site gets verifiers for offline sign-in, and only hashes")
    void offlineVerifiersAreDistributedAsHashesOnly() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");

        var distributed = authority.factorsFor("pin");

        assertEquals(1, distributed.size());
        assertEquals("albus@hogwarts.scot", distributed.get(0).getKey());
        assertFalse(distributed.get(0).getValue().contains("4815"),
                "what reaches a second site must verify a PIN and be unable to produce one");
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
                "a second site holds verifiers for people who can sign in there, and nobody else");
    }

    @Test
    @Timeout(300)
    @DisplayName("the credential is store-authored — it rides a backup and never an export")
    @Proving(DboPromises.AUTH_IDENTITY_AS_RECORDS)
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

    @Test
    @Timeout(300)
    @DisplayName("the tenant says whose each login holding a factor is, and hands out no "
            + "credential that is not active")
    @Proving(DboPromises.AUTH_A_SITE_KEEPS_A_FACTOR_IT_WAS_HANDED_AS_A_HASH)
    void theTenantSaysWhoseEachLoginIs() {
        authority.ensureLocalCredential("minerva@hogwarts.scot", "test1234", "prac-3");
        authority.setFactor("minerva@hogwarts.scot", "pin", "1066");
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");

        java.util.Map<String, String> whose = new java.util.TreeMap<>();
        authority.holdersOf("pin").forEach(h -> whose.put(h.login(), h.personId()));
        assertEquals("prac-1", whose.get("albus@hogwarts.scot"));
        assertEquals("prac-3", whose.get("minerva@hogwarts.scot"));

        authority.retireCredential("minerva@hogwarts.scot");
        assertTrue(authority.holdersOf("pin").stream()
                        .noneMatch(h -> h.login().equals("minerva@hogwarts.scot")),
                "a retired credential's factor was handed out");
    }

    @Test
    @Timeout(300)
    @DisplayName("a site keeps a factor it was handed as a hash, for a login it never held, and "
            + "the matching secret verifies there and a wrong one does not")
    @Proving(DboPromises.AUTH_A_SITE_KEEPS_A_FACTOR_IT_WAS_HANDED_AS_A_HASH)
    void aSiteKeepsAFactorItWasHandedAsAHash() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");

        handedToTheSite("pin");

        assertTrue(site.verifyFactor("albus@hogwarts.scot", "pin", "4815"),
                "the site did not keep the PIN it was handed");
        assertFalse(site.verifyFactor("albus@hogwarts.scot", "pin", "1234"));
        assertTrue(site.holdersOf("pin").stream().anyMatch(h ->
                        h.login().equals("albus@hogwarts.scot") && h.personId().equals("prac-1")),
                "the site's credential does not belong to the person the tenant named");
    }

    @Test
    @Timeout(300)
    @DisplayName("handed a new hash, a site replaces the factor and keeps the login's others")
    @Proving(DboPromises.AUTH_A_SITE_KEEPS_A_FACTOR_IT_WAS_HANDED_AS_A_HASH)
    void aNewHashReplacesTheFactor() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");
        authority.setFactor("albus@hogwarts.scot", "otp", "162342");
        handedToTheSite("pin");
        handedToTheSite("otp");

        authority.setFactor("albus@hogwarts.scot", "pin", "2718");
        handedToTheSite("pin");

        assertFalse(site.verifyFactor("albus@hogwarts.scot", "pin", "4815"),
                "the old PIN still signs in at the site");
        assertTrue(site.verifyFactor("albus@hogwarts.scot", "pin", "2718"));
        assertTrue(site.verifyFactor("albus@hogwarts.scot", "otp", "162342"),
                "replacing one factor took another with it");
    }

    @Test
    @Timeout(300)
    @DisplayName("a site refuses a hash this store would not produce, never keeps a password "
            + "from one, and does not move a login to another person")
    @Proving(DboPromises.AUTH_A_SITE_KEEPS_A_FACTOR_IT_WAS_HANDED_AS_A_HASH)
    void whatASiteWillNotKeep() {
        assertThrows(IllegalArgumentException.class,
                () -> site.keepFactor("severus@hogwarts.scot", "prac-4", "pin", "4815"));
        assertThrows(IllegalArgumentException.class,
                () -> site.keepFactor("severus@hogwarts.scot", "prac-4", "pin",
                        "pbkdf2$100000$c2hvcnQ=$c2hvcnQ="));
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");
        String hash = authority.holdersOf("pin").stream()
                .filter(h -> h.login().equals("albus@hogwarts.scot")).findFirst().orElseThrow()
                .hash();
        assertThrows(IllegalArgumentException.class,
                () -> site.keepFactor("severus@hogwarts.scot", "prac-4", "pwd", hash),
                "a site kept a password from a hash");

        site.keepFactor("albus@hogwarts.scot", "prac-1", "pin", hash);
        assertThrows(IllegalArgumentException.class,
                () -> site.keepFactor("albus@hogwarts.scot", "prac-9", "pin", hash),
                "a hash handed for a login moved it to another person");
    }

    @Test
    @Timeout(300)
    @DisplayName("a factor a site forgets no longer verifies there, and its other factors still do")
    @Proving(DboPromises.AUTH_A_SITE_KEEPS_A_FACTOR_IT_WAS_HANDED_AS_A_HASH)
    void aForgottenFactorNoLongerVerifies() {
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");
        authority.setFactor("albus@hogwarts.scot", "otp", "162342");
        handedToTheSite("pin");
        handedToTheSite("otp");

        site.forgetFactor("albus@hogwarts.scot", "pin");

        assertFalse(site.verifyFactor("albus@hogwarts.scot", "pin", "4815"));
        assertTrue(site.verifyFactor("albus@hogwarts.scot", "otp", "162342"));
    }

    @Test
    @Timeout(300)
    @DisplayName("a wrong PIN is not recognised where the authority serves a place, and the "
            + "tenant does not recognise even the right one")
    @Proving(DboPromises.AUTH_A_PLACE_SIGNS_IN_WITH_A_PIN)
    void aPinSignsInOnlyWhereAPlaceIsServed() {
        String app = "a-sign-in-app";
        String back = "http://127.0.0.1/back";
        // A public client proves it asked: without a challenge it is turned away
        // before any secret is looked at, and every refusal would look alike.
        String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        for (TenantAuthority either : List.of(authority, site)) {
            either.ensureClient(app, null, List.of("user/*.read"), "public-pkce", List.of(back));
        }
        authority.setFactor("albus@hogwarts.scot", "pin", "4815");
        handedToTheSite("pin");
        site.signsInWith(java.util.Set.of("pin"));

        // That the right one signs somebody in, with what it grants and the
        // token it issues, is the sign-in story's: nobody here holds a role.
        assertTrue(site.completeLoginWith(app, back, CHALLENGE, null, "albus@hogwarts.scot", "pin",
                        "1234") instanceof TenantAuthority.LoginResult.NotRecognised,
                "the site recognised a wrong PIN");
        assertTrue(authority.completeLoginWith(app, back, CHALLENGE, null, "albus@hogwarts.scot",
                        "pin", "4815") instanceof TenantAuthority.LoginResult.NotRecognised,
                "the tenant, which keeps PINs only to hand them out, signed somebody in with one");
    }
}
