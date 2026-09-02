package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A participant offers the public half of its own keypair when it enrols, and
 * from then on what it may open is decided by what it holds.
 *
 * <p>The enrolment is the headless machine-credential registration on the
 * tenant's authority — the only enrolment there is — so the key is offered
 * there, as a JWK beside the secret. The store records it, names the version
 * by its thumbprint, and wraps payload data keys to it. The private half is
 * generated on the participant's side and never sent, which is the property
 * every assertion here comes back to: a copy of everything the store holds
 * about the participant opens nothing.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AParticipantOffersItsKeyAtEnrolmentIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final byte[] kek = new byte[32];
    static final HttpClient http = HttpClient.newHttpClient();
    static TenantAuthority sideAuthority;
    static PgObjectStore identityStore;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        new SecureRandom().nextBytes(kek);
        dir = Files.createTempDirectory("dbo-enrol");
        String jdbcUrl = SharedPostgres.urlFor("AParticipantOffersItsKeyAtEnrolmentIT");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve("meristem.json"), """
                {"code":"meristem","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, "meristem");
        PGSimpleDataSource ds = new PGSimpleDataSource();
        // The tenant's own database, beside the harness's, as the provisioner names it.
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "tenant_meristem");
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        identityStore = new PgObjectStore(ds, IdentityModel.registrations());
        sideAuthority = new TenantAuthority(identityStore,
                "http://127.0.0.1:" + manager.port() + "/t/meristem/oidc", new KeyProtector(kek));
    }

    @AfterAll
    void down() throws Exception {
        manager.close();
    }

    @Test
    @DisplayName("enrolled with a key: the store records the public half, names it by its "
            + "thumbprint, and only the holder of the private half unwraps what is wrapped to it")
    @Proving(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT)
    void whatAParticipantHoldsDecidesWhatItMayOpen() throws Exception {
        // The analyser generates its keypair before it is ever enrolled.
        KeyPair analyser = KeyWrap.newParticipantKeyPair();
        ParticipantKey offered = ParticipantKey.of(analyser.getPublic());

        HttpResponse<String> enrolled = enrol("analyser-7", "s3cr3t", offered.render());
        assertEquals(200, enrolled.statusCode(), enrolled.body());
        assertTrue(enrolled.body().contains("\"kid\":\"" + offered.kid() + "\""),
                "the store names the version it will wrap to, and that name is the key's own "
                        + "thumbprint rather than a counter it minted: " + enrolled.body());

        // What the store recorded is the public half and nothing else.
        Optional<ParticipantKey> recorded = sideAuthority.participantKey("analyser-7");
        assertEquals(Optional.of(offered), recorded, "the key offered is the key recorded");
        String record = clientRecord("analyser-7");
        assertTrue(record.contains("\"kty\":\"OKP\"") && record.contains("\"crv\":\"X25519\""),
                record);
        assertFalse(record.contains("\"d\":"), "the private half never crossed: " + record);

        // Wrapped to the recorded key; opened only by the holder.
        byte[] dataKey = new byte[32];
        new SecureRandom().nextBytes(dataKey);
        KeyWrap.Wrapped wrapped = KeyWrap.wrap(dataKey, recorded.get());
        assertEquals(offered.kid(), wrapped.kid(), "a wrap names the version it was made to");
        assertArrayEquals(dataKey, KeyWrap.unwrap(wrapped, analyser.getPrivate()),
                "the participant, holding the private half, opens it");
        assertArrayEquals(dataKey,
                KeyWrap.unwrap(KeyWrap.Wrapped.parse(wrapped.render()), analyser.getPrivate()),
                "and the wrap survives being carried as text");

        // A copy of the enrolment records opens nothing: everything the store
        // holds — the record, the wrap — plus a keypair that is not the
        // participant's fails the tag rather than yielding something.
        KeyPair impostor = KeyWrap.newParticipantKeyPair();
        assertThrows(GeneralSecurityException.class,
                () -> KeyWrap.unwrap(wrapped, impostor.getPrivate()),
                "the carrier, holding the record and the wrap and no private half, opens nothing");
        // And a wrap re-labelled to another version fails too: the version
        // is authenticated, not decorative.
        KeyWrap.Wrapped relabelled = new KeyWrap.Wrapped(
                ParticipantKey.of(impostor.getPublic()).kid(),
                wrapped.ephemeral(), wrapped.iv(), wrapped.ciphertext());
        assertThrows(GeneralSecurityException.class,
                () -> KeyWrap.unwrap(relabelled, analyser.getPrivate()));
    }

    @Test
    @DisplayName("re-enrolling with a new key rotates the version: the old wrap names a kid "
            + "the holder no longer has, and the new key opens only what was wrapped to it")
    @Proving(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT)
    void aVersionSurvivesTheWrap() throws Exception {
        KeyPair first = KeyWrap.newParticipantKeyPair();
        KeyPair second = KeyWrap.newParticipantKeyPair();
        assertEquals(200, enrol("analyser-8", "s3cr3t",
                ParticipantKey.of(first.getPublic()).render()).statusCode());
        KeyWrap.Wrapped underFirst = KeyWrap.wrap(new byte[32],
                sideAuthority.participantKey("analyser-8").orElseThrow());

        // Same credential, new key: the caller's key is authoritative, as its
        // secret is, and re-ensuring is saying what the record should be.
        HttpResponse<String> rotated = enrol("analyser-8", "s3cr3t",
                ParticipantKey.of(second.getPublic()).render());
        assertEquals(200, rotated.statusCode(), rotated.body());
        ParticipantKey current = sideAuthority.participantKey("analyser-8").orElseThrow();
        assertEquals(ParticipantKey.of(second.getPublic()), current);
        assertNotEquals(underFirst.kid(), current.kid(),
                "the version changed with the key, and the old wrap says which it was made to");
        assertThrows(GeneralSecurityException.class,
                () -> KeyWrap.unwrap(underFirst, second.getPrivate()),
                "the new key does not open what was wrapped to the old one");
        assertArrayEquals(new byte[32], KeyWrap.unwrap(underFirst, first.getPrivate()),
                "the old key still does, which is the holder's business to retire");
    }

    @Test
    @DisplayName("enrolled without a key: nothing to wrap to, and the store says so rather "
            + "than sealing under something the carrier could hold")
    @Proving(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT)
    void aParticipantWithoutAKeyHasNothingToBeSealedTo() throws Exception {
        assertEquals(200, enrol("router-1", "s3cr3t", null).statusCode());
        assertEquals(Optional.empty(), sideAuthority.participantKey("router-1"),
                "a credential is not a key; a participant that offered none is sealed to by nobody");
    }

    @Test
    @DisplayName("a JWK carrying the private half, or not an X25519 key at all, is refused "
            + "rather than recorded")
    @Proving(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT)
    void onlyThePublicHalfOfTheRightKindIsAccepted() throws Exception {
        String leaked = "{\"kty\":\"OKP\",\"crv\":\"X25519\",\"x\":\""
                + "hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo\",\"d\":\"never\"}";
        HttpResponse<String> refused = enrol("analyser-9", "s3cr3t", leaked);
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("only the public half"), refused.body());
        assertTrue(clientRecords("analyser-9").isEmpty(),
                "a refused enrolment records nothing, private half included");

        HttpResponse<String> wrongKind = enrol("analyser-9", "s3cr3t",
                "{\"kty\":\"RSA\",\"n\":\"AQAB\",\"e\":\"AQAB\"}");
        assertEquals(400, wrongKind.statusCode(), wrongKind.body());
        assertTrue(wrongKind.body().contains("X25519"), wrongKind.body());
    }

    // ------------------------------------------------------------ fixtures

    private HttpResponse<String> enrol(String clientId, String secret, String publicKeyJwk)
            throws Exception {
        String body = "{\"client_id\":\"" + clientId + "\",\"secret\":\"" + secret
                + "\",\"scope\":[\"system/*.read\"]"
                + (publicKeyJwk != null ? ",\"public_key\":" + publicKeyJwk : "") + "}";
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/admin/clients"))
                        .header("Authorization", "Bearer " + serviceToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String clientRecord(String clientId) {
        List<String> records = clientRecords(clientId);
        assertEquals(1, records.size(), records.toString());
        return records.get(0);
    }

    private static List<String> clientRecords(String clientId) {
        return identityStore.select(Criteria.of("ClientApplication")).stream()
                .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                .filter(json -> json.contains("\"clientId\":\"" + clientId + "\""))
                .toList();
    }

    private static String base() {
        return "http://127.0.0.1:" + manager.port() + "/t/meristem";
    }

    private static String serviceToken() throws Exception {
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(provisioner.bootstrapClientSecret("meristem"), StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }
}
