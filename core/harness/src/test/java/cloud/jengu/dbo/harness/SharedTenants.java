package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ONE runtime and a handful of tenants for the whole IT suite, so a class
 * that only needs somewhere to write does not pay a bring-up for it.
 *
 * <p><b>Why this exists.</b> A tenant costs about three and a half seconds to
 * stand up — a fresh database, its schema, and the face's vocabulary ingested
 * into it — and the suite was doing that seventy-nine times for tenants that
 * differed in nothing a test cared about. That is two thirds of the wall
 * clock spent proving the same bring-up over and over.
 *
 * <p><b>Tenants are keyed by SHAPE, not by class.</b> Two classes wanting an
 * r4 tenant with internal identity get the same one. What a tenant cannot
 * share is configuration — a face binds one version, a type declares one
 * identity class, isolation is on or off — so the shapes below are the
 * distinct configurations the suite actually asks for, and nothing more.
 *
 * <p><b>What a class gives up by sharing, and must therefore not do.</b>
 * Counting. A shared tenant holds whatever every other class put in it, so an
 * assertion of the form "there is exactly one X" or "this type is empty"
 * passes or fails on test ordering, which is the worst defect to debug. Scope
 * every assertion to the record, run or code the test itself just made.
 *
 * <p><b>When to take a private tenant instead.</b> Reading a change feed and
 * counting events, anything about a quiet tenant, tenant lifecycle itself,
 * and anything that drops or erases the tenant. Those are about the tenant
 * rather than about something in it, and they are why {@link
 * TenantRuntimeManager} is still constructed directly in a few places.
 *
 * <p>Brought up lazily: a run that touches one shape pays for one.
 */
public final class SharedTenants {

    /** The distinct configurations the suite asks for. */
    public enum Shape {

        /** r4, everything internal — somewhere to write when identity is not the point. */
        R4_INTERNAL("""
                [{"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"},
                 {"name":"Encounter","identity":"internal","handling":"operational"},
                 {"name":"Basic","identity":"internal","handling":"operational"}]""", "r4", false),

        /** r4 with patients keyed by the national identifier, plus canonical content. */
        R4_IDENTIFIER("""
                [{"name":"Patient","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"},
                 {"name":"Encounter","identity":"internal","handling":"operational"},
                 {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                 {"name":"ValueSet","identity":"canonical","handling":"operational"},
                 {"name":"StructureDefinition","identity":"canonical","handling":"operational"}]"""
                .formatted(EID), "r4", false),

        /** r4 behind the isolation membrane, with the person types a directory writes. */
        R4_ISOLATED("""
                [{"name":"Patient","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Person","identity":"internal","handling":"operational"},
                 {"name":"Practitioner","identity":"internal","handling":"operational"}]"""
                .formatted(EID), "r4", true),

        /** r5, for anything that has to be served beside r4 rather than instead of it. */
        R5("""
                [{"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"}]""",
                "r5", false);

        private final String types;
        private final String face;
        private final boolean isolated;

        Shape(String types, String face, boolean isolated) {
            this.types = types;
            this.face = face;
            this.isolated = isolated;
        }

        /** The tenant code, which is the shape's own name: shared means shared. */
        public String code() {
            return "shared" + name().toLowerCase(java.util.Locale.ROOT).replace("_", "");
        }
    }

    /** The identifier system the identifier-keyed shapes are declared against. */
    public static final String EID = "https://ee.ee/eid";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<Shape, Tenant> UP = new ConcurrentHashMap<>();
    private static final TenantRuntimeManager MANAGER = start();
    private static Path directory;

    private SharedTenants() {
    }

    private static TenantRuntimeManager start() {
        try {
            directory = Files.createTempDirectory("dbo-shared-tenants");
            LocalDatabasePerTenantProvisioner provisioner = new LocalDatabasePerTenantProvisioner(
                    SharedPostgres.urlFor("sharedtenants"),
                    SharedPostgres.username(), SharedPostgres.password());
            byte[] kek = new byte[32];
            new java.security.SecureRandom().nextBytes(kek);
            TenantRuntimeManager manager = new TenantRuntimeManager(directory, provisioner,
                    "127.0.0.1", 0, null, new TenantRuntimeManager.AuthorityConfig(kek, null));
            // Never closed from a class's @AfterAll: the runtime outlives any
            // one of them now, and closing it would pull the floor out from
            // under whatever is still running beside it.
            return manager;
        } catch (Exception e) {
            throw new IllegalStateException("the shared runtime did not start", e);
        }
    }

    /** The tenant of this shape, brought up on first ask and shared after it. */
    public static synchronized Tenant of(Shape shape) {
        return UP.computeIfAbsent(shape, s -> {
            try {
                Files.writeString(directory.resolve(s.code() + ".json"), """
                        {"code":"%s","face":"%s"%s,"audit":{"level":"full"},"types":%s}"""
                        .formatted(s.code(), s.face, s.isolated ? ",\"pdi\":true" : "",
                                s.types));
                UntilServed.scan(MANAGER, s.code());
                return new Tenant(s.code());
            } catch (Exception e) {
                throw new IllegalStateException("shared tenant " + s.code() + " did not "
                        + "come up", e);
            }
        });
    }

    /** One shared tenant, and everything a test needs to reach it. */
    public record Tenant(String code) {

        /** Its FHIR surface, for the tests that drive real HTTP. */
        public String fhir() {
            return MANAGER.baseUrl(code);
        }

        /** The runtime's own base, for the doors that sit beside the face. */
        public String base() {
            return "http://127.0.0.1:" + MANAGER.port() + "/t/" + code;
        }

        public ObjectStore engine() {
            return MANAGER.runtime(code).orElseThrow().engine();
        }

        public ChangeFeed feed() {
            return MANAGER.runtime(code).orElseThrow().feed();
        }

        public TenantAuthority authority() {
            return MANAGER.authority(code);
        }

        /**
         * A credential of this test's own, with the scopes it needs.
         *
         * <p>The client id is the caller's to choose and should name the test
         * that holds it: credentials accumulate on a shared tenant, and one
         * called {@code client} tells nobody which class minted it.
         */
        public String token(String clientId, String... scopes) {
            authority().ensureClient(clientId, clientId + "-secret", List.of(scopes));
            try {
                String form = "grant_type=client_credentials&client_id="
                        + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                        + "&client_secret="
                        + URLEncoder.encode(clientId + "-secret", StandardCharsets.UTF_8);
                HttpResponse<String> issued = HTTP.send(HttpRequest.newBuilder(
                                        URI.create(base() + "/oidc/token"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                        HttpResponse.BodyHandlers.ofString());
                return issued.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
            } catch (Exception e) {
                throw new IllegalStateException("no token for " + clientId, e);
            }
        }
    }

    /** The shared runtime, for the few tests that need the manager itself. */
    public static TenantRuntimeManager manager() {
        return MANAGER;
    }
}
