package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityHub;
import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.Jwk;
import cloud.jengu.dbo.auth.Jws;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.auth.ZoneModel;
import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.definitions.DefinitionStore;
import cloud.jengu.dbo.definitions.FaceFunctions;
import cloud.jengu.dbo.definitions.FaceImage;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.FaceWarmup;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deployment is equipped before it starts, and what it was given takes effect
 * exactly as given: the technical story
 * {@link cloud.jengu.dbo.promises.DboStories#A_DEPLOYMENT_IS_EQUIPPED}.
 *
 * <p>Whoever runs a deployment decides some things before any tenant exists:
 * where face images are kept, the bootstrap secret each tenant's authority will
 * accept, the national broker its identity hub federates to, and the secrets of
 * the brokers its zones name. The legs walk each of those in turn:
 *
 * <ul>
 *   <li><b>Where images are kept.</b> A face cut once loads into an empty
 *   database and puts the same rows there; an image from another release, or
 *   one with no manifest, is refused by name; a tenant brought up from the image
 *   is the tenant the chain would have made, and a face root after the first
 *   loads the face rather than reading the packages again.</li>
 *   <li><b>A secret it chose.</b> The secret custody holds opens that tenant's
 *   surface, and that tenant's only.</li>
 *   <li><b>The brokers a zone names.</b> Each member runs its contracted broker,
 *   a stricter member's policy adds its own ceremony to the same session, and
 *   the accumulated session then serves both with none further.</li>
 *   <li><b>The broker the hub federates to.</b> One ceremony serves every
 *   tenant, authorisation stays per tenant, and a person the hub identifies
 *   holds no password.</li>
 *   <li><b>A zone on another face.</b> It is converted once, by a projection
 *   nobody declared, which leaves an image in the deployment's image store; a
 *   definition the older face cannot stand up is named, and a tenant arriving
 *   after that loss is refused rather than served most of the zone.</li>
 * </ul>
 *
 * <p><b>Why not on Rowling Land.</b> Every leg depends on something handed to
 * the runtime when it is built or before its first scan: an image directory, a
 * secret in custody, the hub's upstream, broker secrets by code. The one shared
 * world is given none of these, and a world that had been given them would make
 * every story run under them. So this class builds one runtime of its own.
 *
 * <p><b>One runtime for every leg.</b> One {@link TenantRuntimeManager.AuthorityConfig}
 * carries both kinds of federation: the hub's upstream, which serves a tenant
 * outside any zone, and the broker secrets, which the hub of a zone reads its
 * brokers' credentials from. A tenant in a zone federates through its zone's
 * hub and never through the deployment's, so the two do not meet. The image
 * directory is a setting of the running deployment, and the image legs set it
 * between bring-ups as the originals did. One stub server plays every broker,
 * each under a path of its own and with a ceremony counter of its own, so each
 * count is exactly what its leg asserts.
 *
 * <p><b>One r4 face root for the image legs and the projection legs.</b> Its
 * code sorts before every other root this class declares. A projection's face
 * root is the first root of its face in the deployment's declarations, so the
 * roots the image legs add later never change which root the projection
 * stands on.
 *
 * <p><b>Two subject systems on purpose.</b> The hub's configured subject system
 * is not the zone's. A zone's members resolve people by the system the zone
 * declares and fall back to the configured one only when it declares none; with
 * the two equal, a zone whose declaration was never read would pass as one that
 * was.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ADeploymentIsEquippedBeforeItStartsIT {

    // ------------------------------------------------------------- the face

    /** The one r4 face root; sorts before every other root declared here. */
    private static final String FACE_ROOT = "alusjuur";
    private static final String RELEASE = "test-release";
    private static final String READ_THE_CHAIN = "pilt-ahelast";
    private static final String FROM_THE_IMAGE = "pilt-kujutisest";

    // ------------------------------------------------------- the custody pair

    private static final String CUSTODY_ONE = "custody-one";
    private static final String CUSTODY_TWO = "custody-two";
    private static final String ONE_SECRET = "the-secret-this-deployment-chose";
    private static final String TWO_SECRET = "a-different-one-entirely";

    // ------------------------------------------------------ the brokers

    /** The system the zone declares as its person identifier domain. */
    static final String ZONE_SUBJECT_SYSTEM = "https://fhir.ee/sid/pid/est/ni";
    /** The system the deployment's hub is configured with; not the zone's. */
    static final String HUB_SUBJECT_SYSTEM = "https://hub.example/sid/national";
    static final String ISIKUKOOD = "37001010021";
    static final String REDIRECT = "http://127.0.0.1/cb";
    /** The broker the deployment's own hub federates to. */
    static final String NATIONAL = "national";

    // ------------------------------------------- the zone on another face

    private static final String PROJECTED_ZONE = "vald";
    private static final String ON_R4 = "vald-on-r4";
    private static final String SAME_FACE = "sama-nagu";
    private static final String OTHER_FACE = "teine-nagu";
    private static final String EID = "https://zone.test/id";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final byte[] KEK = new byte[32];

    static HttpServer brokers;
    static final Map<String, KeyPair> brokerKeys = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> ceremonies = new ConcurrentHashMap<>();

    /** Plain requests: tokens, writes, the custody legs. */
    static final HttpClient HTTP = HttpClient.newHttpClient();
    /** The browser that signs in at the zone's members. */
    static final HttpClient zoneBrowser = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager()).build();
    /** A different browser, for the deployment hub's tenants. */
    static final HttpClient hubBrowser = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager()).build();

    static byte[] image;
    static FaceImage.Manifest cut;
    static Map<String, Long> rootAtTheCut;
    static long cutMillis;
    static Path images;
    static long chainMillis;
    static long imageMillis;
    static Path projectionImages;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        new SecureRandom().nextBytes(KEK);
        brokers = stubBrokers();
        String brokerBase = "http://127.0.0.1:" + brokers.getAddress().getPort();

        dir = Files.createTempDirectory("dbo-equipped");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ADeploymentIsEquippedBeforeItStartsIT"),
                postgres.getUsername(), postgres.getPassword());
        // What custody holds, named before anything is provisioned — which is
        // the order a deployment actually has: the secret exists in its vault
        // before the tenant it belongs to exists.
        provisioner.bootstrapSecrets(Map.of(CUSTODY_ONE, ONE_SECRET, CUSTODY_TWO, TWO_SECRET));
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(KEK, null,
                        new IdentityHub.Upstream(brokerBase + "/" + NATIONAL,
                                "zone-hub", "hub-secret", "EE"),
                        HUB_SUBJECT_SYSTEM,
                        Map.of("tara", "tara-salajane", "eeid", "eeid-salajane")));

        // The face root reads its packages and cuts the face into the
        // directory the projection will later take it from.
        projectionImages = Files.createTempDirectory("dbo-zone-images");
        manager.faceImagesIn(projectionImages);
        Files.writeString(dir.resolve(FACE_ROOT + ".json"), root(FACE_ROOT));

        // Written out per tenant rather than in a loop: the check that no two
        // harness classes claim one code reads these names statically, and a
        // loop variable is a name it cannot resolve.
        Files.writeString(dir.resolve(CUSTODY_ONE + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"identifier",
                   "systems":["https://custody.example/nid"],
                   "handling":"operational"}]}""".formatted(CUSTODY_ONE));
        Files.writeString(dir.resolve(CUSTODY_TWO + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"identifier",
                   "systems":["https://custody.example/nid"],
                   "handling":"operational"}]}""".formatted(CUSTODY_TWO));

        // The zone tenant — its declarations are records, and it says it is
        // one: a member naming a tenant that has not declared itself a
        // jurisdiction is refused.
        Files.writeString(dir.resolve("ee.json"), """
                {"code":"ee","face":"r4","zoneRoot":true,
                 "types":[{"name":"Basic","identity":"internal","handling":"operational"}]}""");

        // The clinics the deployment's own hub serves, in no zone.
        for (String clinic : List.of("kliinika", "kliinikb", "kliinikc")) {
            Files.writeString(dir.resolve(clinic + ".json"), """
                    {"code":"%s","face":"r4","types":[
                      {"name":"Patient","identity":"internal","handling":"operational"},
                      {"name":"Person","identity":"identifier","systems":["%s"],"handling":"operational"},
                      {"name":"Practitioner","identity":"identifier","systems":["%s"],"handling":"operational"},
                      {"name":"PractitionerRole","identity":"internal","handling":"operational"}]}"""
                    .formatted(clinic, HUB_SUBJECT_SYSTEM, HUB_SUBJECT_SYSTEM));
        }
        // Waited for, not scanned once. A pass is one reconciliation, not a
        // promise that it finished.
        UntilServed.scan(manager, FACE_ROOT, CUSTODY_ONE, CUSTODY_TWO, "ee",
                "kliinika", "kliinikb", "kliinikc");

        theZoneDeclaresItsBrokers(brokerBase);
        theDoctorWorksAtTheHubsClinics();

        theFaceIsCut();
        aTenantIsBroughtUpEachWay();
        aZoneIsWrittenOnAnotherFace();
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
        if (brokers != null) {
            brokers.stop(0);
        }
    }

    /** The zone's brokers and identifier domain, then its two members. */
    private void theZoneDeclaresItsBrokers(String brokerBase) throws Exception {
        PgObjectStore zoneStore = new PgObjectStore(databaseOf("ee"), ZoneModel.registrations());
        // As the lane, because that is what this stands in for: a zone's
        // brokers and identifier domains are projected configuration, and
        // projected configuration is refused to everybody but the lane that
        // applies it. A test seeding them as a tenant user would be exercising
        // a door no declarer has.
        zoneStore.put(PutRequest.create("ZoneBroker", ZoneModel.Broker.payload(
                new ZoneModel.Broker("tara", brokerBase + "/tara", "zone-tara", "EE", "high"))),
                cloud.jengu.dbo.core.api.Handling.Authority.CONFIG_LANE);
        zoneStore.put(PutRequest.create("ZoneBroker", ZoneModel.Broker.payload(
                new ZoneModel.Broker("eeid", brokerBase + "/eeid", "zone-eeid", "EE",
                        "substantial"))),
                cloud.jengu.dbo.core.api.Handling.Authority.CONFIG_LANE);
        zoneStore.put(PutRequest.create("ZoneIdentifierDomain",
                ZoneModel.identifierDomainPayload(ZoneModel.USE_PERSON_PRIMARY,
                        ZONE_SUBJECT_SYSTEM)),
                cloud.jengu.dbo.core.api.Handling.Authority.CONFIG_LANE);

        // haigla: municipal (tara, tara-only policy); kliinik: private (eeid, accepts any)
        Files.writeString(dir.resolve("haigla.json"), """
                {"code":"haigla","face":"r4","zone":"ee","broker":"tara",
                 "acceptedBrokers":["tara"],"types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"Practitioner","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"}]}"""
                .formatted(ZONE_SUBJECT_SYSTEM, ZONE_SUBJECT_SYSTEM));
        Files.writeString(dir.resolve("kliinik.json"), """
                {"code":"kliinik","face":"r4","zone":"ee","broker":"eeid","types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"Practitioner","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"}]}"""
                .formatted(ZONE_SUBJECT_SYSTEM, ZONE_SUBJECT_SYSTEM));
        // Waited for: a token asked of a tenant that is not serving yet is
        // answered 404 by the surface — which is also what a tenant nobody
        // declared answers, so a setup that scanned once died naming neither.
        UntilServed.scan(manager, "haigla", "kliinik");

        for (String member : List.of("haigla", "kliinik")) {
            String service = serviceToken(member);
            String practitioner = idOf(fhirPost(member, "/Practitioner", service, """
                    {"resourceType":"Practitioner",
                     "identifier":[{"system":"%s","value":"%s"}]}"""
                    .formatted(ZONE_SUBJECT_SYSTEM, ISIKUKOOD)));
            // the human behind the clinician, carrying the national identifier
            fhirPost(member, "/Person", service, """
                    {"resourceType":"Person",
                     "identifier":[{"system":"%s","value":"%s"}],
                     "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                    .formatted(ZONE_SUBJECT_SYSTEM, ISIKUKOOD, practitioner));
            fhirPost(member, "/PractitionerRole", service, """
                    {"resourceType":"PractitionerRole",
                     "practitioner":{"reference":"Practitioner/%s"},
                     "code":[{"coding":[{"system":"urn:example:role","code":"doctor"}]}]}"""
                    .formatted(practitioner));
            TenantAuthority side = sideAuthority(member);
            side.ensureRoleGrant("doctor", List.of("user/*.read"));
            side.ensureClient("webapp", null, List.of("user/*.read"),
                    "public-pkce", List.of(REDIRECT));
        }
    }

    /** The doctor works at clinics A and B — not C. */
    private void theDoctorWorksAtTheHubsClinics() throws Exception {
        for (String employer : List.of("kliinika", "kliinikb")) {
            String service = serviceToken(employer);
            String practitioner = idOf(fhirPost(employer, "/Practitioner", service, """
                    {"resourceType":"Practitioner",
                     "identifier":[{"system":"%s","value":"%s"}]}"""
                    .formatted(HUB_SUBJECT_SYSTEM, ISIKUKOOD)));
            // the human the hub will assert, with the clinician as a relation
            HttpResponse<String> personCreated = fhirPost(employer, "/Person", service, """
                    {"resourceType":"Person",
                     "identifier":[{"system":"%s","value":"%s"}],
                     "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                    .formatted(HUB_SUBJECT_SYSTEM, ISIKUKOOD, practitioner));
            assertEquals(201, personCreated.statusCode());
            String person = idOf(personCreated);
            assertEquals(201, fhirPost(employer, "/PractitionerRole", service, """
                    {"resourceType":"PractitionerRole",
                     "practitioner":{"reference":"Practitioner/%s"},
                     "code":[{"coding":[{"system":"urn:example:role","code":"doctor"}]}]}"""
                    .formatted(practitioner)).statusCode());
            sideAuthority(employer).ensureRoleGrant("doctor", List.of("user/*.read"));
            // A password and a bench PIN, set BEFORE anybody federates —
            // which is the situation the rule has to survive: an organisation
            // adopting an eID has people already holding credentials, and
            // refusing the federation until somebody tidies them up would fail
            // at exactly the wrong moment.
            sideAuthority(employer).ensureLocalCredential("arst@" + employer, "vanaparool",
                    person);
            sideAuthority(employer).setFactor("arst@" + employer, "pin", "4711");
            sideAuthority(employer).ensureClient("webapp", null,
                    List.of("user/*.read", "user/*.write"), "public-pkce", List.of(REDIRECT));
        }
        sideAuthority("kliinikc").ensureClient("webapp", null,
                List.of("user/*.read"), "public-pkce", List.of(REDIRECT));
    }

    /** The face cut to bytes, before anything else has read it. */
    private void theFaceIsCut() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long began = System.currentTimeMillis();
        cut = FaceImage.cut(rootSource(), facts(), "a-cursor-the-feed-minted", out);
        cutMillis = System.currentTimeMillis() - began;
        image = out.toByteArray();
        // What the face held at the cut, kept now: the root goes on serving
        // subscribers and a projection after this, and the image is compared
        // with what it was cut from rather than with what the root became.
        rootAtTheCut = rowsPerTable(rootSource());
    }

    /** The same tenant by two routes: through the chain, and from an image. */
    private void aTenantIsBroughtUpEachWay() throws Exception {
        images = Files.createTempDirectory("dbo-image-kept");

        // One reads the chain. Said explicitly rather than relied on: the build
        // keeps images for every other suite, and this one is the comparison.
        manager.faceImagesIn(null);
        Files.writeString(dir.resolve(READ_THE_CHAIN + ".json"), subscriber(READ_THE_CHAIN));
        long began = System.currentTimeMillis();
        UntilServed.scan(manager, READ_THE_CHAIN);
        chainMillis = System.currentTimeMillis() - began;

        // The face is cut, and the next one is brought up from it.
        assertInstanceOf(FaceWarmup.Outcome.Cut.class,
                FaceWarmup.cut(manager, FACE_ROOT, images));
        manager.faceImagesIn(images);
        Files.writeString(dir.resolve(FROM_THE_IMAGE + ".json"), subscriber(FROM_THE_IMAGE));
        began = System.currentTimeMillis();
        UntilServed.scan(manager, FROM_THE_IMAGE);
        imageMillis = System.currentTimeMillis() - began;
    }

    /** A zone in R5, one tenant on its face and one on R4. */
    private void aZoneIsWrittenOnAnotherFace() throws Exception {
        // The face the zone will be converted INTO is the root above; without
        // one the projection has nothing to judge a converted definition
        // against.
        manager.faceImagesIn(projectionImages);

        // The zone publishes in R5.
        Files.writeString(dir.resolve(PROJECTED_ZONE + ".json"), """
                {"code":"%s","face":"r5","audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Observation","identity":"identifier","systems":["%s"],
                   "handling":"operational"}]}"""
                .formatted(PROJECTED_ZONE, EID));
        // One tenant is on R5 like the zone, and one is on R4 and is not.
        Files.writeString(dir.resolve(SAME_FACE + ".json"), tenant(SAME_FACE, "r5"));
        Files.writeString(dir.resolve(OTHER_FACE + ".json"), tenant(OTHER_FACE, "r4"));

        UntilServed.scan(manager, PROJECTED_ZONE);
        UntilServed.scan(manager, SAME_FACE, OTHER_FACE);
    }

    // =============================================== where images are kept

    @Test
    @Order(1)
    @Timeout(600)
    @DisplayName("an image cut from a face puts the same rows in an empty database")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void anImageCutFromAFacePutsTheSameRowsBack() throws Exception {
        assertTrue(cut.rows() > 1000,
                "a face is the whole of what a version publishes, and this cut " + cut.rows());

        PGSimpleDataSource fresh = emptyDatabaseWithTheSchema("face_image_target");
        long began = System.currentTimeMillis();
        FaceImage.Acceptance answer = FaceImage.accept(
                fresh, facts(), new ByteArrayInputStream(image));
        long acceptMillis = System.currentTimeMillis() - began;

        FaceImage.Acceptance.Accepted accepted = assertInstanceOf(
                FaceImage.Acceptance.Accepted.class, answer,
                answer instanceof FaceImage.Acceptance.Refused refused ? refused.why() : "");
        assertEquals(cut.cursor(), accepted.manifest().cursor(),
                "the position the face stood at did not travel with it, so a tenant brought "
                        + "up from this would not know where to catch up from");

        assertEquals(rootAtTheCut, rowsPerTable(fresh),
                "a tenant brought up from the image does not hold what the face holds");

        // A face root's natural bring-up is around half a minute; what
        // replaces it for every tenant after the first is the accept below.
        System.out.printf("METRICS faceImage rows=%d bytes=%d cutMs=%d acceptMs=%d%n",
                cut.rows(), image.length, cutMillis, acceptMillis);
        assertTrue(acceptMillis < 20_000,
                "bringing a face up from its image took " + acceptMillis + "ms, which is not "
                        + "obviously better than reading the whole face through a chain");
    }

    @Test
    @Order(2)
    @Timeout(600)
    @DisplayName("an image from another release is refused, and says which part disagreed")
    @Proving(DboPromises.VER_AN_IMAGE_FROM_ANOTHER_RELEASE_IS_REFUSED)
    void anImageFromAnotherReleaseIsRefused() throws Exception {
        assertRefused(new FaceImage.Facts(RELEASE, "r5", facts().faceSql(), facts().shape()),
                "r5", "a face's image came up on another face");
        assertRefused(new FaceImage.Facts(RELEASE, "r4", facts().faceSql(), facts().shape() + 1),
                "shape", "rows expanded by one expander were read by the checks of another");
        assertRefused(new FaceImage.Facts(RELEASE, "r4", "a-different-fingerprint",
                        facts().shape()),
                "face SQL", "rows shaped for one release's checks were read by another's");
        assertRefused(new FaceImage.Facts("some-other-release", "r4", facts().faceSql(),
                        facts().shape()),
                "release", "an image from another release was loaded anyway");
    }

    @Test
    @Order(3)
    @Timeout(600)
    @DisplayName("an image with no manifest is not an image")
    @Proving(DboPromises.VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE)
    void anImageWithNoManifestIsNotAnImage() throws Exception {
        byte[] halfWritten = withoutTheManifest(image);
        PGSimpleDataSource fresh = emptyDatabaseWithTheSchema("face_image_half");

        FaceImage.Acceptance answer = FaceImage.accept(
                fresh, facts(), new ByteArrayInputStream(halfWritten));

        FaceImage.Acceptance.Refused refused = assertInstanceOf(
                FaceImage.Acceptance.Refused.class, answer,
                "an image a job died halfway through was loaded as though it were whole");
        assertTrue(refused.why().contains("manifest"), refused.why());
        assertEquals(0, rowsIn(fresh, Domains.tables(Domains.DEFINITIONS) + "_data"),
                "the refusal had already loaded some of it");
    }

    @Test
    @Order(4)
    @Timeout(600)
    @DisplayName("an image is brought up from, never merged into")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void anImageIsNotMergedIntoWhatIsAlreadyThere() throws Exception {
        PGSimpleDataSource fresh = emptyDatabaseWithTheSchema("face_image_twice");
        assertInstanceOf(FaceImage.Acceptance.Accepted.class,
                FaceImage.accept(fresh, facts(), new ByteArrayInputStream(image)));

        FaceImage.Acceptance again = FaceImage.accept(
                fresh, facts(), new ByteArrayInputStream(image));

        FaceImage.Acceptance.Refused refused = assertInstanceOf(
                FaceImage.Acceptance.Refused.class, again,
                "the face was loaded twice, so every definition it holds is now there twice");
        assertTrue(refused.why().contains("already holds rows"), refused.why());
    }

    @Test
    @Order(5)
    @Timeout(600)
    @DisplayName("warmup cuts the face where the operator keeps it, and leaves nothing partial")
    @Proving(DboPromises.VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE)
    void warmupCutsTheFaceWhereTheOperatorKeepsIt() throws Exception {
        Path kept = Files.createTempDirectory("dbo-face-images");

        FaceWarmup.Outcome outcome = FaceWarmup.cut(manager, FACE_ROOT, kept);

        FaceWarmup.Outcome.Cut done = assertInstanceOf(FaceWarmup.Outcome.Cut.class, outcome,
                outcome instanceof FaceWarmup.Outcome.NotYet notYet ? notYet.why() : "");
        assertTrue(Files.exists(done.image()), "warmup said it cut a face and wrote no file");
        assertEquals("r4", done.manifest().facts().face());
        assertTrue(done.manifest().rows() > 1000,
                "the face it cut holds " + done.manifest().rows() + " rows");
        assertTrue(done.manifest().cursor() != null && !done.manifest().cursor().isBlank(),
                "the image does not say where the face stood, so a tenant brought up from it "
                        + "would not know what it still has to catch up on");

        // Written through a temporary name so a reader never opens one that is
        // half there. Nothing of that may survive the cutting.
        try (var listed = Files.list(kept)) {
            assertEquals(List.of("r4.faceimage"),
                    listed.map(f -> f.getFileName().toString())
                            // The lock is how one cutting at a time is kept to
                            // one; it is not something the cutting left behind.
                            .filter(name -> !name.endsWith(".lock"))
                            .sorted().toList(),
                    "the cutting left something behind beside the image it wrote");
        }

        // And what warmup wrote is an image, on this release's own terms.
        assertInstanceOf(FaceImage.Acceptance.Accepted.class,
                FaceImage.accept(emptyDatabaseWithTheSchema("face_image_warmed"),
                        done.manifest().facts(),
                        new ByteArrayInputStream(Files.readAllBytes(done.image()))),
                "warmup wrote something this release would not accept");
    }

    @Test
    @Order(6)
    @Timeout(600)
    @DisplayName("what is not a face root is not cut into a face")
    @Proving(DboPromises.VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE)
    void whatIsNotAFaceRootIsNotCut() throws Exception {
        Path kept = Files.createTempDirectory("dbo-face-images-refused");

        FaceWarmup.Outcome outcome = FaceWarmup.cut(manager, "a-tenant-nobody-declared", kept);

        FaceWarmup.Outcome.NotYet notYet = assertInstanceOf(FaceWarmup.Outcome.NotYet.class,
                outcome, "a tenant that is not serving was cut into a face anyway");
        assertTrue(notYet.why().contains("a-tenant-nobody-declared"), notYet.why());
        try (var listed = Files.list(kept)) {
            assertEquals(List.of(), listed.toList(),
                    "nothing was cut and a file was written anyway");
        }
    }

    @Test
    @Order(7)
    @Timeout(600)
    @DisplayName("a position the face never reached is not stood at")
    @Proving(DboPromises.VER_AN_IMAGE_FROM_ANOTHER_RELEASE_IS_REFUSED)
    void aPositionTheFaceNeverReachedIsNotStoodAt() throws Exception {
        cloud.jengu.dbo.postgres.PgChangeFeed face = new cloud.jengu.dbo.postgres.PgChangeFeed(
                rootSource(), Domains.DEFINITIONS);
        String cutAt = face.headCursor();
        assertTrue(cutAt != null, "the face published nothing, so there is nothing to test");

        // The face has been where its own image says, which is the normal case
        // and the one that must keep working.
        assertTrue(face.hasReached(cutAt),
                "a face does not recognise the position its own image was cut at");
        assertTrue(face.hasReached(null), "every feed has reached the beginning");

        // A root rebuilt from its packages starts its feed over, and an image
        // that outlived it names somewhere the new one has never been. Nothing
        // in the manifest says so: the release, the packages and the SQL all
        // still agree.
        cloud.jengu.dbo.postgres.PgChangeFeed rebuilt = new cloud.jengu.dbo.postgres.PgChangeFeed(
                emptyDatabaseWithTheSchema("face_image_rebuilt"), Domains.DEFINITIONS);
        assertFalse(rebuilt.hasReached(cutAt),
                "a feed that has published nothing claimed to have reached a position from "
                        + "another database, so a tenant would stand past everything the "
                        + "rebuilt face publishes and skip it in silence");
    }

    @Test
    @Order(8)
    @Timeout(900)
    @DisplayName("the two tenants hold the same face, table for table")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void theTwoTenantsHoldTheSameFace() throws Exception {
        Map<String, Long> read = faceOf(READ_THE_CHAIN);
        Map<String, Long> loaded = faceOf(FROM_THE_IMAGE);

        assertTrue(read.getOrDefault(Domains.DEFINITIONS + "_data", 0L) > 1000,
                "the tenant that read the chain does not hold a face: " + read);
        assertEquals(read, loaded,
                "a tenant brought up from the image holds something other than what the "
                        + "tenant that read the chain holds");

        System.out.printf("METRICS faceBringUp chainMs=%d imageMs=%d%n",
                chainMillis, imageMillis);
    }

    @Test
    @Order(9)
    @Timeout(900)
    @DisplayName("its rows say they came from the face, as the chain would have said")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void itsRowsSayWhereTheyCameFrom() throws Exception {
        long marked = count(FROM_THE_IMAGE,
                "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS)
                        + "_sync_origin WHERE dependency = '" + FACE_ROOT + "'");
        long held = count(FROM_THE_IMAGE,
                "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS) + "_data");

        // Not every definition a tenant holds came from the face: the runtime
        // writes the face's own vocabulary into each tenant itself, after the
        // chain has run, and those are the tenant's own. What must match is
        // how much came from the FACE, and that is the same by either route.
        assertTrue(marked > 1000 && marked <= held,
                "the tenant brought up from the image marked " + marked + " of " + held
                        + " definitions as having come from its face");
        assertEquals(count(READ_THE_CHAIN,
                        "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS)
                                + "_sync_origin WHERE dependency = '" + FACE_ROOT + "'"),
                marked,
                "the two tenants disagree about how much of their face came from the face, "
                        + "so a read of one reports a different source than a read of the "
                        + "other and the next change from upstream matches differently");
    }

    @Test
    @Order(10)
    @Timeout(900)
    @DisplayName("a definition published after the cut still arrives, once")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void aDefinitionPublishedAfterTheCutStillArrives() throws Exception {
        manager.runtime(FACE_ROOT).orElseThrow().store().create("""
                {"resourceType":"StructureDefinition","url":"urn:test:after-the-cut",
                 "name":"AfterTheCut","status":"draft","kind":"resource","abstract":false,
                 "type":"Patient","baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[{"id":"Patient","path":"Patient"}]}}""");

        // Whatever a round does for the tenant that read the chain, it does
        // for the one that did not: the stream was left where the image was
        // cut, so the only thing outstanding is what came after it.
        manager.streamsOf(FROM_THE_IMAGE).forEach(s -> {
            while (s.syncOnce(500) > 0) {
                // drain
            }
        });

        assertEquals(1, count(FROM_THE_IMAGE,
                        "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS)
                                + "_data WHERE envelope::text LIKE '%urn:test:after-the-cut%'"),
                "a definition published after the image was cut either never reached the "
                        + "tenant brought up from it, or reached it more than once");
    }

    // ================================================== a secret it chose

    @Test
    @Order(13)
    @DisplayName("the secret the deployment chose is the one the tenant's authority accepts, so "
            + "a guarded surface is reachable by whoever runs it")
    @Proving(DboPromises.AUTH_BOOTSTRAP_SECRET_IS_CUSTODY)
    void theDeploymentsOwnSecretOpensItsTenant() throws Exception {
        String token = custodyToken(CUSTODY_ONE, ONE_SECRET);
        assertNotEquals("", token, "no token came back for the secret custody holds");

        HttpResponse<String> guarded = HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(CUSTODY_ONE) + "/metadata"))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, guarded.statusCode(), guarded.body());
        assertTrue(guarded.body().contains("CapabilityStatement"), guarded.body());
    }

    @Test
    @Order(14)
    @DisplayName("and it opens that tenant only: a credential is custody for one tenant, never "
            + "a key to the deployment")
    @Proving(DboPromises.AUTH_BOOTSTRAP_SECRET_IS_CUSTODY)
    void oneTenantsCredentialIsNotAnothers() throws Exception {
        // The right secret, presented to the wrong tenant's authority. If this
        // ever succeeds, the per-tenant map has become a deployment-wide key
        // and the isolation everything else rests on is decoration.
        String wrong = custodyToken(CUSTODY_TWO, ONE_SECRET);
        assertEquals("", wrong,
                "one tenant's bootstrap secret authenticated against another tenant");

        String right = custodyToken(CUSTODY_TWO, TWO_SECRET);
        assertNotEquals("", right, "the second tenant's own secret was refused");
        assertNotEquals(custodyToken(CUSTODY_ONE, ONE_SECRET), right,
                "two tenants issued the same token, so the issuer is not per tenant");
    }

    // ============================================ the brokers a zone names

    /** The private clinic first: one eeID ceremony, subject system FROM THE ZONE. */
    @Test
    @Order(15)
    @DisplayName("a private clinic in the zone runs the broker it contracted, once, and finds "
            + "its clinician by the zone's own identifier system")
    @Proving({DboPromises.AUTH_FEDERATED_HUMANS, DboPromises.ZONE_BROKER_CHOICE,
            DboPromises.ZONE_SUBJECT_DOMAINS})
    void privateClinicRunsItsContractedBrokerOnce() throws Exception {
        String token = signIn(zoneBrowser, "kliinik", "", 10);
        assertTrue(token.startsWith("ey"), token);
        assertEquals(1, ceremonies.get("eeid").get());
        assertEquals(0, ceremonies.get("tara").get());
    }

    /** The hospital's tara-only policy is NOT satisfied by the eeid ceremony. */
    @Test
    @Order(16)
    @DisplayName("a hospital that accepts only the government broker runs that ceremony too, "
            + "onto the same session, and the private broker is not run again")
    @Proving({DboPromises.ZONE_BROKER_CHOICE, DboPromises.ZONE_SESSIONS_ACCUMULATE})
    void municipalPolicyTriggersItsOwnCeremonyOntoTheSameSession() throws Exception {
        String token = signIn(zoneBrowser, "haigla", "", 10);
        assertTrue(token.startsWith("ey"), token);
        assertEquals(1, ceremonies.get("tara").get(), "the required broker's ceremony ran");
        assertEquals(1, ceremonies.get("eeid").get(), "eeid was NOT re-run");
    }

    /** The accumulated session now serves everyone with zero further ceremonies. */
    @Test
    @Order(17)
    @DisplayName("the session that has gathered both ceremonies serves both clinics with no "
            + "further one")
    @Proving({DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS, DboPromises.ZONE_SESSIONS_ACCUMULATE})
    void theAccumulatedSessionServesEveryoneFreely() throws Exception {
        assertTrue(signIn(zoneBrowser, "kliinik", "", 10).startsWith("ey"));
        assertTrue(signIn(zoneBrowser, "haigla", "", 10).startsWith("ey"));
        assertEquals(1, ceremonies.get("eeid").get());
        assertEquals(1, ceremonies.get("tara").get());
    }

    // ===================================== the broker the hub federates to

    /** One ceremony at clinic A: the full chain through the hub and the broker. */
    @Test
    @Order(18)
    @DisplayName("the first sign-in runs the national ceremony once and names the clinician "
            + "it resolved")
    @Proving({DboPromises.AUTH_FEDERATED_HUMANS, DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS})
    void firstLoginRunsTheNationalCeremonyOnce() throws Exception {
        String token = signIn(hubBrowser, "kliinika", "&state=zzz", 8);
        String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"fhirUser\":\"Practitioner/"), claims);
        assertEquals(1, ceremonies.get(NATIONAL).get(), "exactly one billable ceremony");
    }

    /** Clinic B rides the hub session: a second tenant, ZERO further ceremonies. */
    @Test
    @Order(19)
    @DisplayName("a second clinic is signed in on the hub's session, with no further ceremony")
    @Proving(DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS)
    void secondTenantCostsNoCeremony() throws Exception {
        String token = signIn(hubBrowser, "kliinikb", "&state=zzz", 8);
        assertTrue(token.startsWith("ey"), token);
        assertEquals(1, ceremonies.get(NATIONAL).get(),
                "the hub session must serve the second tenant without the broker");
    }

    /** Clinic C: same valid identity, no grant — authorization is never shared. */
    @Test
    @Order(20)
    @DisplayName("a clinic that granted the same person nothing denies them, and the denial "
            + "costs no ceremony")
    @Proving({DboPromises.AUTH_FEDERATED_HUMANS, DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS})
    void aTenantWithoutAGrantDeniesTheSameIdentity() throws Exception {
        assertEquals("error:access_denied", signIn(hubBrowser, "kliinikc", "&state=zzz", 8));
        assertEquals(1, ceremonies.get(NATIONAL).get(), "denial costs no ceremony either");
    }

    @Test
    @Order(21)
    @DisplayName("signing in through the hub records that the hub identifies this person, "
            + "which is what the password rule reads")
    @Proving(DboPromises.AUTH_PASSWORD_ONLY_WHERE_WE_ARE_THE_IDP)
    void federatingRecordsTheBinding() throws Exception {
        // The sign-ins above already happened. What is asserted here is that
        // they left the trace the rule depends on: without it, "does this
        // subject federate?" has no signal and a refusal reading it never fires.
        String person = hubPerson();

        assertFalse(sideAuthority("kliinika").externalIdentityProvidersOf(person).isEmpty(),
                "the hub signed this person in and nothing recorded that it identifies them, "
                        + "so the store cannot tell a federated subject from one it is the "
                        + "identity provider for");
    }

    @Test
    @Order(22)
    @DisplayName("and a password for them is then refused at both doors, naming where they "
            + "sign in — while a bench PIN still works")
    @Proving(DboPromises.AUTH_PASSWORD_ONLY_WHERE_WE_ARE_THE_IDP)
    void aFederatedSubjectHoldsNoPassword() throws Exception {
        String person = hubPerson();

        // The password they held before is gone, and the PIN they held beside
        // it is not. That asymmetry is the rule: a password beside a federated
        // identity is a second way in that never reaches the identity
        // provider, while a bench PIN is the factor for the case federation
        // cannot serve — a bench with no network — and taking it away would
        // remove the fallback for the situation the rule was written around.
        assertFalse(sideAuthority("kliinika").holdsPassword("arst@kliinika"),
                "the password survived federation, so there is still a way in that never "
                        + "reaches the identity provider");
        assertTrue(sideAuthority("kliinika").verifyFactor("arst@kliinika", "pin", "4711"),
                "the bench PIN was retired along with the password, which takes away the "
                        + "fallback for the case federation cannot serve");

        IllegalArgumentException atTheFirstDoor = assertThrows(IllegalArgumentException.class,
                () -> sideAuthority("kliinika").ensureLocalCredential(
                        "arst", "parool", person));
        // Asked of the store rather than spelt here: the refusal has to name
        // the provider this person is actually bound to, and a hardcoded
        // string would pass while naming the wrong one.
        String provider = sideAuthority("kliinika").externalIdentityProvidersOf(person)
                .iterator().next();
        assertTrue(atTheFirstDoor.getMessage().contains(provider),
                "the refusal must say where they sign in instead, or somebody goes looking "
                        + "for a fault in the store: " + atTheFirstDoor.getMessage());

        // The other door. A rule enforced at one door and open at the other is
        // not enforced.
        IllegalArgumentException atTheOtherDoor = assertThrows(IllegalArgumentException.class,
                () -> sideAuthority("kliinika").setFactor("arst@kliinika", "pwd", "parool"));
        assertTrue(atTheOtherDoor.getMessage().contains(provider), atTheOtherDoor.getMessage());

        // And a PIN through that same door is still allowed for them, which is
        // what makes the refusal above about the password rather than about
        // the login.
        sideAuthority("kliinika").setFactor("arst@kliinika", "pin", "1234");
        assertTrue(sideAuthority("kliinika").verifyFactor("arst@kliinika", "pin", "1234"));

        // Somebody this tenant IS the identity provider for is untouched.
        sideAuthority("kliinika").ensureLocalCredential("kohalik", "parool", "keegi-teine");
        assertTrue(sideAuthority("kliinika").holdsPassword("kohalik"),
                "the rule refused a password for somebody who federates nowhere, so it is "
                        + "not reading the binding — it is refusing everybody");
    }

    // ===================================== a zone on another face

    @Test
    @Order(23)
    @Timeout(900)
    @DisplayName("a zone on another face is converted once, by a tenant nobody declared")
    @Proving(DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION)
    void aZoneOnAnotherFaceIsConvertedOnce() {
        assertTrue(manager.codes().contains(ON_R4),
                "nothing stands between the R5 zone and its R4 tenant, so that tenant "
                        + "converts the zone for itself: " + manager.codes());
        assertEquals("r4", manager.runtime(ON_R4).orElseThrow().spec().face(),
                "the projection does not stand on the face it exists to serve");

        // And only where the versions differ: the tenant on the zone's own
        // face reads the zone itself, because there is nothing to convert.
        assertFalse(manager.codes().contains(PROJECTED_ZONE + "-on-r5"),
                "a projection was made for a face the zone was already written in, which "
                        + "is a hop, a database and a second copy for no conversion");
    }

    @Test
    @Order(24)
    @Timeout(900)
    @DisplayName("the tenant reads the projection while still declaring the zone")
    @Proving(DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION)
    void theTenantReadsTheProjectionWhileDeclaringTheZone() throws Exception {
        // What it asked for is unchanged: a tenant names the zone, and which
        // projection serves it follows from its own face.
        assertEquals(List.of(PROJECTED_ZONE), manager.runtime(OTHER_FACE).orElseThrow().spec()
                        .dependencies().stream().map(d -> d.name()).toList(),
                "the tenant's declaration names something other than the zone it asked for");

        // The stream is still NAMED for the zone, and that is not an oversight:
        // provenance says where a record came from as far as the tenant is
        // concerned, and the tenant asked for the zone.
        assertTrue(manager.streamsOf(OTHER_FACE).stream()
                        .anyMatch(stream -> stream.name().equals("sync." + PROJECTED_ZONE + "."
                                + OTHER_FACE)),
                "the stream is named for something other than the zone the tenant declared: "
                        + manager.streamsOf(OTHER_FACE).stream().map(s -> s.name()).toList());

        // Something has to have travelled before there is anything to see: a
        // reader leaves its position where it read, and a reader that has read
        // nothing has left nothing.
        publishToTheZone();

        // What it READS is the projection, and a reader of a feed leaves its
        // position in the database it read from — so the R4 tenant's consumer
        // is the projection's to keep, and the zone has never heard of it.
        assertTrue(readsFrom(ON_R4, "r4", "sync." + PROJECTED_ZONE + "." + OTHER_FACE),
                "the R4 tenant does not read the projection, so it is converting the "
                        + "zone for itself");
        assertFalse(readsFrom(PROJECTED_ZONE, "r5", "sync." + PROJECTED_ZONE + "." + OTHER_FACE),
                "the R4 tenant reads the R5 zone directly, across versions");

        // And the projection reads the zone, which is the one hop that converts.
        assertTrue(readsFrom(PROJECTED_ZONE, "r5", "sync." + PROJECTED_ZONE + "." + ON_R4),
                "the projection does not read the zone it exists to convert");

        // The tenant on the zone's own face reads the zone itself.
        assertTrue(readsFrom(PROJECTED_ZONE, "r5", "sync." + PROJECTED_ZONE + "." + SAME_FACE),
                "a tenant on the zone's own face was routed through a projection anyway");
    }

    @Test
    @Order(25)
    @Timeout(900)
    @DisplayName("what the zone publishes arrives on the other face, converted once")
    @Proving(DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION)
    void whatTheZonePublishesArrivesConverted() throws Exception {
        publishToTheZone();

        // It reached the tenant on the zone's own face, unconverted, and the
        // tenant on the other face, converted — and the conversion happened at
        // the projection, which is the only place that crosses versions.
        long published = PUBLISHED.get();
        assertEquals(published, holds(SAME_FACE, "r5"),
                "the zone's own face did not receive what it published");
        assertEquals(published, holds(ON_R4, "r4"),
                "the projection did not take the zone across the version");
        assertEquals(published, holds(OTHER_FACE, "r4"),
                "the tenant on the other face never received the zone's record");
    }

    @Test
    @Order(26)
    @Timeout(900)
    @DisplayName("a definition the older face cannot stand up is named, not passed on")
    @Proving(DboPromises.ZONE_WHAT_CONVERSION_CANNOT_CARRY_IS_REFUSED_BY_NAME)
    void aDefinitionTheOlderFaceCannotStandUpIsNamed() throws Exception {
        // Nothing here is unservable yet, and saying so is half the point: a
        // check that always finds something is a check nobody reads.
        assertEquals(List.of(), manager.whatTheVersionCouldNotCarry(ON_R4),
                "a zone that converts cleanly was reported as partly unservable");

        // A profile built on a resource the older version never had. It
        // converts — the document is well-formed either way — and what comes
        // out stands on nothing, which is the failure worth catching: it
        // loads, and nothing can be validated against it.
        manager.runtime(PROJECTED_ZONE).orElseThrow().store().create("""
                {"resourceType":"StructureDefinition","url":"https://zone.test/only-in-r5",
                 "name":"OnlyInR5","status":"draft","kind":"resource","abstract":false,
                 "type":"ActorDefinition",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/ActorDefinition",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"ActorDefinition","path":"ActorDefinition"}]}}""");
        for (int round = 0; round < 10 && manager.syncRound() > 0; round++) {
            // carried as far as it goes
        }
        manager.syncRound();

        List<cloud.jengu.dbo.tenant.ConvertedDefinitions.Unfounded> lost =
                manager.whatTheVersionCouldNotCarry(ON_R4);
        assertTrue(lost.stream().anyMatch(one -> "https://zone.test/only-in-r5".equals(one.url())),
                "a profile standing on a resource this face never had was carried across as "
                        + "though it still stood on something: " + lost);
        assertTrue(lost.stream().anyMatch(one ->
                        one.base().contains("ActorDefinition")),
                "the report does not say what it lost, which is the part somebody can act "
                        + "on: " + lost);
    }

    @Test
    @Order(27)
    @Timeout(900)
    @DisplayName("a tenant is not served a zone that did not survive the trip to its face")
    @Proving(DboPromises.ZONE_AN_UNSERVABLE_ZONE_IS_SAID_AT_BRING_UP)
    void aTenantIsNotServedAZoneThatDidNotSurvive() throws Exception {
        // The zone lost a definition on the way to R4 in the leg before this
        // one, which is why these are ordered: what is being asked here is
        // what a tenant arriving AFTER that is told.

        Files.writeString(dir.resolve("hiljem-tulija.json"), tenant("hiljem-tulija", "r4"));
        for (int pass = 0; pass < 5; pass++) {
            manager.scanOnce();
        }

        assertFalse(manager.codes().contains("hiljem-tulija"),
                "a tenant came up serving most of a zone, which looks exactly like serving "
                        + "the zone and is not");
        String why = manager.troubles().get("hiljem-tulija");
        assertTrue(why != null && why.contains(PROJECTED_ZONE),
                "the refusal does not name the zone that could not be served: " + why);
        assertTrue(why.contains("only-in-r5"),
                "the refusal does not name the definition that was lost, which is the part "
                        + "somebody can act on: " + why);

        // And a tenant on the zone's own face is unaffected: nothing was
        // converted, so nothing can have been lost.
        Files.writeString(dir.resolve("hiljem-r5.json"), tenant("hiljem-r5", "r5"));
        UntilServed.scan(manager, "hiljem-r5");
        assertTrue(manager.codes().contains("hiljem-r5"),
                "a tenant on the zone's own face was refused for a loss that only happens "
                        + "on the way to another one");
    }

    @Test
    @Order(28)
    @Timeout(900)
    @DisplayName("a canonical the face already gave the projection does not stop the zone's "
            + "stream")
    @Proving(DboPromises.SYNC_LOCAL_SHADOWING)
    void aCanonicalTheFaceGaveUsDoesNotStopTheZone() {
        // A tenant can be given the same canonical by two upstreams: the
        // engine's own vocabulary reaches it with its face, and again with any
        // zone that publishes structures. The second arrival is a conflict
        // over identity, and what the stream does with it decides whether
        // everything behind it is ever delivered. A stream that rethrew it
        // would stop at the head of its queue for good, with no dead letter
        // and no parked shadow to say so.
        var zoneStream = manager.streamsOf(ON_R4).stream()
                .filter(s -> s.name().equals("sync." + PROJECTED_ZONE + "." + ON_R4
                        + ".definitions"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("the projection has no definitions stream for "
                                + "its zone: " + manager.streamsOf(ON_R4).stream()
                                .map(s -> s.name()).toList()));

        assertFalse(zoneStream.origins().isEmpty(),
                "the zone's definitions stream has applied nothing at all, which is what "
                        + "being stuck at the head of its queue looks like from outside");
        assertFalse(zoneStream.degraded(),
                "the zone's definitions stream is degraded: " + zoneStream.deadLetters());
    }

    @Test
    @Order(29)
    @Timeout(900)
    @DisplayName("the projection leaves an image, and it carries the zone as well as the face")
    @Proving(DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION)
    void theProjectionLeavesAnImageCarryingTheZoneAndTheFace() throws Exception {
        // A tenant of this zone on this face wants what the projection holds:
        // the face AND the zone converted into it. The face's own image
        // carries only half of that, and nobody but the projection can make
        // the other half — so the projection leaves one behind.
        Path projected = projectionImages.resolve(ON_R4 + ".faceimage");
        assertTrue(Files.exists(projected),
                "the projection left nothing behind, so every tenant of this zone reads the "
                        + "converted zone through the chain: "
                        + java.util.Arrays.toString(projectionImages.toFile().list()));
        assertTrue(Files.size(projected) > 1_000_000,
                "what it left is too small to be a face with a zone in it: "
                        + Files.size(projected));

        // And it needs no sealing, which is why it can be a file at all: an
        // image is of the definitions schema, and records are not in it. The
        // zone's observations reached this projection and are not in its
        // definitions.
        assertEquals(0, definitionsHolding(ON_R4, "Observation"),
                "a record is in the schema the image is cut from, so the image carries "
                        + "somebody's data and may not travel as plain bytes");
        assertTrue(holds(ON_R4, "r4") > 0,
                "the projection holds no records at all, so the assertion above proves "
                        + "nothing about where records go");
    }

    // ======================================================= the brokers

    /**
     * Every broker this deployment is given, on one stub server: the zone's
     * two under {@code /tara} and {@code /eeid}, and the hub's under
     * {@code /national}. Each auto-approves, signs an id_token for the same
     * person, and counts its own ceremonies.
     */
    private static HttpServer stubBrokers() throws Exception {
        for (String code : List.of("tara", "eeid", NATIONAL)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            brokerKeys.put(code, generator.generateKeyPair());
            ceremonies.put(code, new AtomicInteger());
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String[] segments = path.split("/");
            String broker = segments.length > 1 && brokerKeys.containsKey(segments[1])
                    ? segments[1] : null;
            String response;
            int status = 200;
            if (broker == null) {
                response = "{}";
                status = 404;
            } else {
                String brokerBase = base + "/" + broker;
                // The zone's brokers know the hub of the zone by the client id
                // the zone declared; the national one knows the deployment's
                // hub by the id it was configured with.
                String clientId = NATIONAL.equals(broker) ? "zone-hub" : "zone-" + broker;
                if (path.endsWith("/.well-known/openid-configuration")) {
                    response = "{\"issuer\":\"" + brokerBase + "\""
                            + ",\"authorization_endpoint\":\"" + brokerBase + "/authorize\""
                            + ",\"token_endpoint\":\"" + brokerBase + "/token\""
                            + ",\"jwks_uri\":\"" + brokerBase + "/jwks\"}";
                } else if (path.endsWith("/authorize")) {
                    // THE billable ceremony
                    ceremonies.get(broker).incrementAndGet();
                    String query = exchange.getRequestURI().getRawQuery();
                    exchange.getResponseHeaders().set("Location",
                            param(query, "redirect_uri") + "?code=" + broker + "-code&state="
                                    + param(query, "state"));
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                } else if (path.endsWith("/token")) {
                    long now = System.currentTimeMillis() / 1000;
                    String idToken = Jws.sign(broker + "-kid",
                            "{\"iss\":\"" + brokerBase + "\",\"aud\":\"" + clientId + "\""
                                    + ",\"sub\":\"EE" + ISIKUKOOD + "\""
                                    + ",\"iat\":" + now + ",\"exp\":" + (now + 300) + "}",
                            brokerKeys.get(broker).getPrivate());
                    response = NATIONAL.equals(broker)
                            ? "{\"id_token\":\"" + idToken + "\",\"access_token\":\"n/a\""
                                    + ",\"token_type\":\"Bearer\"}"
                            : "{\"id_token\":\"" + idToken + "\"}";
                } else if (path.endsWith("/jwks")) {
                    response = "{\"keys\":[" + Jwk.render(broker + "-kid",
                            (RSAPublicKey) brokerKeys.get(broker).getPublic()) + "]}";
                } else {
                    response = "{}";
                    status = 404;
                }
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return java.net.URLDecoder.decode(pair.substring(name.length() + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ================================================ authority helpers

    private TenantAuthority sideAuthority(String code) {
        return new TenantAuthority(new PgObjectStore(databaseOf(code),
                IdentityModel.registrations()), base(code) + "/oidc", new KeyProtector(KEK));
    }

    private String base(String code) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + code;
    }

    /** The Person the hub resolved at clinic A. */
    private String hubPerson() {
        return manager.runtime("kliinika").orElseThrow().engine()
                .getByIdentifier("Person", List.of(new cloud.jengu.dbo.core.api.Identifier(
                        HUB_SUBJECT_SYSTEM, ISIKUKOOD)))
                .stream().findFirst().orElseThrow().id();
    }

    private String serviceToken(String code) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base(code) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                                        + URLEncoder.encode(provisioner.bootstrapClientSecret(code),
                                                StandardCharsets.UTF_8))).build(),
                HttpResponse.BodyHandlers.ofString());
        // Asserted rather than pattern-matched out: a refusal taken for a token
        // is sent on as a credential, and the failure surfaces somewhere else
        // with no trace of what went wrong here.
        assertEquals(200, response.statusCode(),
                "no service token for '" + code + "': " + response.body());
        java.util.regex.Matcher token = java.util.regex.Pattern
                .compile("\"access_token\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(token.find(),
                "the token answer for '" + code + "' carried no access_token: "
                        + response.body());
        return token.group(1);
    }

    /** The token for a bootstrap secret, or "" when the authority refused it. */
    private static String custodyToken(String code, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(code).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (answer.statusCode() != 200) {
            return "";
        }
        return Extracted.tokenIn(answer.body());
    }

    private HttpResponse<String> fhirPost(String code, String path, String token, String body)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base(code) + "/fhir" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The id of what was just written, or what the store said instead.
     *
     * <p>Reported with the status and the body: an ordinary refusal, a tenant
     * that was not serving yet and a credential that was never a credential
     * all arrive here without a Location, and only the answer tells them apart.
     */
    private static String idOf(HttpResponse<String> created) {
        String location = created.headers().firstValue("Location").orElseThrow(
                () -> new AssertionError("a write answered " + created.statusCode()
                        + " with no Location to take an id from: " + created.body()));
        return Extracted.lastSegment(location);
    }

    /**
     * Signs in at a tenant through whichever hub serves it, following the
     * redirects by hand (the browser's cookies ride along) until the relying
     * party's callback, and answers the access token or {@code error:<code>}.
     */
    private String signIn(HttpClient browser, String code, String state, int hops)
            throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(
                        verifier.getBytes(StandardCharsets.US_ASCII)));
        String location = base(code) + "/oidc/authorize?response_type=code&client_id=webapp"
                + state
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";
        for (int hop = 0; hop < hops; hop++) {
            if (location.startsWith(REDIRECT)) {
                if (location.contains("error=")) {
                    return "error:" + Extracted.queryParam(location, "error");
                }
                String authCode = Extracted.queryParam(location, "code");
                HttpResponse<String> tokens = browser.send(HttpRequest.newBuilder(
                                URI.create(base(code) + "/oidc/token"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "grant_type=authorization_code&client_id=webapp&code="
                                                + authCode + "&redirect_uri="
                                                + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                                + "&code_verifier=" + verifier)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, tokens.statusCode(), tokens.body());
                return Extracted.tokenIn(tokens.body());
            }
            HttpResponse<String> hopResponse = browser.send(HttpRequest.newBuilder(
                    URI.create(location)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, hopResponse.statusCode(),
                    "hop to " + location + " answered: " + hopResponse.body());
            location = hopResponse.headers().firstValue("Location").orElseThrow();
        }
        throw new AssertionError("redirect chain did not reach the RP");
    }

    // ===================================================== face helpers

    private static String root(String code) {
        return """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(code);
    }

    private static String subscriber(String code) {
        return """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","face":true,
                                  "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"replicated"},
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code, FACE_ROOT);
    }

    private void assertRefused(FaceImage.Facts expected, String namesThePart, String because)
            throws Exception {
        PGSimpleDataSource fresh = emptyDatabaseWithTheSchema(
                "face_image_refused_" + Math.abs(expected.hashCode()));
        FaceImage.Acceptance answer = FaceImage.accept(
                fresh, expected, new ByteArrayInputStream(image));

        FaceImage.Acceptance.Refused refused = assertInstanceOf(
                FaceImage.Acceptance.Refused.class, answer, because);
        assertTrue(refused.why().contains(namesThePart),
                "the refusal does not say which part disagreed: " + refused.why());
        assertEquals(0, rowsIn(fresh, Domains.tables(Domains.DEFINITIONS) + "_data"),
                "a refused image had already put rows in: " + refused.why());
    }

    private static FaceImage.Facts facts() {
        return new FaceImage.Facts(RELEASE, "r4",
                FaceFunctions.installedIn(rootSource()), DefinitionStore.SHAPE);
    }

    /** Every table of the definitions schema and how many rows it holds. */
    private static Map<String, Long> rowsPerTable(PGSimpleDataSource on) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection c = on.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = ? AND table_type = 'BASE TABLE'
                     ORDER BY table_name""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), null);
                }
            }
        }
        for (Map.Entry<String, Long> table : counts.entrySet()) {
            table.setValue(rowsIn(on,
                    Domains.schema(Domains.DEFINITIONS) + "." + table.getKey()));
        }
        // What is about this tenant rather than about the face does not travel,
        // and comparing it would be comparing the two tenants instead.
        counts.keySet().removeIf(t -> t.contains("_outbox") || t.contains("_consumer")
                || t.contains("_sync_")
                // The shape marker is about the database, not the face: every
                // store writes its own as it is built, before any face arrives.
                || t.equals("definition_shape")
                // And the compiled parameters, for the same reason. They are
                // the version's own and whatever a tenant authored, so a store
                // has them before a face arrives and an image does not carry
                // them. This target has no store — it is a schema and an image
                // — so it holds none, and comparing them here would compare a
                // bare database against a running tenant.
                || t.equals("definition_parameter"));
        return counts;
    }

    private static long rowsIn(PGSimpleDataSource on, String qualified) throws Exception {
        try (Connection c = on.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + qualified);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * A database as a tenant's bring-up leaves it just before the face
     * arrives: the schema and its tables set up, and nothing in them.
     */
    private PGSimpleDataSource emptyDatabaseWithTheSchema(String name) throws Exception {
        String root = SharedPostgres.urlFor("ADeploymentIsEquippedBeforeItStartsIT");
        try (Connection c = DriverManager.getConnection(root,
                postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement("CREATE DATABASE " + name)) {
            ps.execute();
        } catch (java.sql.SQLException alreadyThere) {
            // a rerun against a kept container
        }
        PGSimpleDataSource fresh = new PGSimpleDataSource();
        fresh.setUrl(root.replaceAll("/[^/?]+(\\?.*)?$", "/" + name));
        fresh.setUser(postgres.getUsername());
        fresh.setPassword(postgres.getPassword());
        copySchemaShape(fresh);
        return fresh;
    }

    /**
     * The empty tables, shaped as the source shapes them.
     *
     * <p>Built column by column rather than with LIKE, which cannot reach
     * across databases, and from the catalogue's own rendering of each type
     * rather than the standard view's — which reports anything Postgres
     * added, xid8 among them, as USER-DEFINED.
     */
    private void copySchemaShape(PGSimpleDataSource into) throws Exception {
        try (Connection from = rootSource().getConnection();
             Connection to = into.getConnection()) {
            try (PreparedStatement schema = to.prepareStatement(
                    "CREATE SCHEMA IF NOT EXISTS " + Domains.schema(Domains.DEFINITIONS))) {
                schema.execute();
            }
            for (String table : tablesOf(from)) {
                try (PreparedStatement ps = to.prepareStatement("CREATE TABLE IF NOT EXISTS "
                        + Domains.schema(Domains.DEFINITIONS) + "." + table
                        + " (" + columnsOf(from, table) + ")")) {
                    ps.execute();
                }
            }
        }
    }

    private static List<String> tablesOf(Connection c) throws Exception {
        List<String> tables = new java.util.ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                ORDER BY table_name""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    private static String columnsOf(Connection from, String table) throws Exception {
        StringBuilder columns = new StringBuilder();
        try (PreparedStatement ps = from.prepareStatement("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!columns.isEmpty()) {
                        columns.append(", ");
                    }
                    columns.append(rs.getString(1)).append(' ').append(rs.getString(2));
                }
            }
        }
        return columns.toString();
    }

    private static byte[] withoutTheManifest(byte[] whole) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream in =
                     new java.util.zip.ZipInputStream(new ByteArrayInputStream(whole));
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (FaceImage.MANIFEST_ENTRY.equals(entry.getName())) {
                    continue;
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                zip.write(in.readAllBytes());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Every table of the tenant's face and how much is in it. */
    private static Map<String, Long> faceOf(String tenant) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection c = databaseOf(tenant).getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = ? AND table_type = 'BASE TABLE'
                     ORDER BY table_name""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    // What is about this tenant's own relationship to a feed
                    // is not the face, and the two tenants reached their
                    // positions by different routes on purpose.
                    if (!table.contains("_outbox") && !table.contains("_consumer")
                            && !table.contains("_sync_")) {
                        counts.put(table, null);
                    }
                }
            }
        }
        for (Map.Entry<String, Long> table : counts.entrySet()) {
            table.setValue(count(tenant, "SELECT count(*) FROM "
                    + Domains.schema(Domains.DEFINITIONS) + "." + table.getKey()));
        }
        return counts;
    }

    private static long count(String tenant, String sql) throws Exception {
        try (Connection c = databaseOf(tenant).getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // =========================================== zone-on-another-face helpers

    /** How many observations the zone has been given; each carries its own number. */
    private static final AtomicInteger PUBLISHED = new AtomicInteger();

    /**
     * One observation into the zone, carried until everything is quiet.
     *
     * <p>Driven rather than waited for, so the leg says when the streams have
     * run instead of guessing how long they take.
     */
    private static void publishToTheZone() {
        // Its own identifier each time: two legs publishing the same one is
        // the zone refusing the second as somebody else's claim, which is the
        // engine being right about identity and has nothing to do with zones.
        manager.runtime(PROJECTED_ZONE).orElseThrow().store().create("""
                {"resourceType":"Observation","status":"final",
                 "identifier":[{"system":"%s","value":"%d"}],
                 "code":{"text":"carried across a version"}}"""
                .formatted(EID, PUBLISHED.incrementAndGet()));
        for (int round = 0; round < 10 && manager.syncRound() > 0; round++) {
            // each round carries what the last one made visible downstream
        }
        manager.syncRound();
    }

    /** How many of that type are in this tenant's DEFINITIONS schema. */
    private static long definitionsHolding(String tenant, String type) throws Exception {
        try (Connection c = databaseOf(tenant).getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS)
                             + "_data WHERE type = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** How many observations this tenant holds. */
    private static long holds(String tenant, String face) throws Exception {
        try (Connection c = databaseOf(tenant).getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM state." + face
                             + "_data WHERE type = 'Observation' AND NOT deleted");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Whether that consumer has a position in this tenant's own feed. */
    private static boolean readsFrom(String tenant, String face, String consumer)
            throws Exception {
        try (Connection c = databaseOf(tenant).getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM state." + face + "_consumer WHERE name = ?")) {
            ps.setString(1, consumer);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static String tenant(String code, String face) {
        return """
                {"code":"%s","face":"%s","audit":{"level":"none"},
                 "dependencies":[{"name":"%s",
                                  "types":["Observation","StructureDefinition"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"Observation","identity":"identifier","systems":["%s"],
                   "handling":"replicated"}]}"""
                .formatted(code, face, PROJECTED_ZONE, EID);
    }

    // ============================================================ databases

    private static PGSimpleDataSource rootSource() {
        return databaseOf(FACE_ROOT);
    }

    /** A tenant's own database, by the name its code gives it. */
    private static PGSimpleDataSource databaseOf(String tenant) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + tenant.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
