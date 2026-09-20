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
        R4_INTERNAL("sharedr4internal", """
                [{"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"},
                 {"name":"Encounter","identity":"internal","handling":"operational"},
                 {"name":"Subscription","identity":"internal","handling":"operational"},
                 {"name":"Basic","identity":"internal","handling":"operational"}]""",
                "r4", "", "full"),

        /** r4 with patients keyed by the national identifier, plus canonical content. */
        R4_IDENTIFIER("sharedr4identifier", """
                [{"name":"Patient","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"},
                 {"name":"Encounter","identity":"internal","handling":"operational"},
                 {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                 {"name":"ValueSet","identity":"canonical","handling":"operational"},
                 {"name":"StructureDefinition","identity":"canonical","handling":"operational"}]"""
                .formatted(EID), "r4", "", "full"),

        /** r4 behind the isolation membrane, with the person types a directory writes. */
        R4_ISOLATED("sharedr4isolated", """
                [{"name":"Patient","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Person","identity":"internal","handling":"operational"},
                 {"name":"Practitioner","identity":"internal","handling":"operational"}]"""
                .formatted(EID), "r4", ",\"pdi\":true", "full"),

        /** r5, for anything that has to be served beside r4 rather than instead of it. */
        R5("sharedr5", """
                [{"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"},
                 {"name":"Subscription","identity":"internal","handling":"operational"},
                 {"name":"SubscriptionTopic","identity":"canonical","handling":"operational"}]""",
                "r5", "", "full"),

        /**
         * r4 for the classes about who may act: an organisation, the people in
         * it, and the role that joins them.
         *
         * <p>Three classes wanted this set and each wrote it out, differing
         * only in which systems they keyed by — which a shape cannot split, so
         * the systems are the shape's.
         */
        R4_GRANTS("sharedr4grants", """
                [{"name":"Organization","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Practitioner","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Person","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"PractitionerRole","identity":"internal","handling":"operational"}]"""
                .formatted(ORGS, LOGINS, LOGINS), "r4", "", "none"),

        /**
         * r4 holding declarations: what a participant says it is, and the
         * organisation a declaration names.
         *
         * <p>Two classes ask about declarations and neither is about a tenant.
         * They chose different identifier systems for the same types, which a
         * shape cannot split — so the systems are the shape's and both classes
         * name them from here.
         */
        R4_DECLARATIONS("sharedr4declarations", """
                [{"name":"ParticipantDeclaration","identity":"identifier","systems":["%s"],
                  "handling":"operational","definition":"none"},
                 {"name":"Organization","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Patient","identity":"internal","handling":"operational"}]"""
                .formatted(PARTICIPANTS, ORGS), "r4", "", "none"),

        /**
         * r4 behind the membrane, with the person keyed by their number.
         *
         * <p>Distinct from {@link #R4_ISOLATED} by which record is keyed how:
         * there the Patient carries the identifier and the Person is
         * internal, here it is the other way round. That is not a detail two
         * shapes can split the difference on — a type declares one identity
         * class — so the family of classes asking about a person behind the
         * membrane gets its own.
         */
        R4_PDI_PERSON("sharedr4pdiperson", """
                [{"name":"Person","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"Practitioner","identity":"internal","handling":"operational"}]"""
                .formatted(EID), "r4", ",\"pdi\":true", "none"),

        /**
         * r4 for converting stock from one shape to another.
         *
         * <p>Two classes wrote this same set out — the profiles a document is
         * held to, the maps that carry it between them, and something ordinary
         * to convert. Each of them then reshapes only what it aimed at, which
         * is what lets them share: a run that converged somebody else's stock
         * would be the bug either of them is looking for.
         */
        R4_RESHAPE("sharedr4reshape", """
                [{"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                 {"name":"StructureMap","identity":"canonical","handling":"operational"},
                 {"name":"Basic","identity":"internal","handling":"operational"}]""",
                "r4", "", "none"),

        /**
         * r4 where people act in their own name and in each other's: the
         * person, the capacity they hold, the role that says so, and
         * something to write with it.
         *
         * <p>Its trail is on, because who acted on whose behalf is the whole
         * of what this family of classes asks — so a class sharing it reads
         * the trail scoped to the record it just wrote, never a page of it.
         */
        R4_DELEGATION("sharedr4delegation", """
                [{"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"Person","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Practitioner","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"PractitionerRole","identity":"internal","handling":"operational"},
                 {"name":"Encounter","identity":"internal","handling":"operational"}]"""
                .formatted(LOGINS, LOGINS), "r4", "", "writes"),

        /**
         * r4 as a zone: vocabularies it publishes, and a configured type
         * projected rather than written.
         *
         * <p>What makes it a zone is the {@code projected-config} handling on
         * a type — a declaration applied through the face rather than a record
         * somebody POSTs — and handling is a type's declaration, so it is a
         * shape rather than something a class can turn on.
         */
        R4_ZONE("sharedr4zone", """
                [{"name":"CodeSystem","identity":"canonical","handling":"operational"},
                 {"name":"ValueSet","identity":"canonical","handling":"operational"},
                 {"name":"Device","identity":"identifier","systems":["%s"],
                  "handling":"projected-config"}]""".formatted(BENCHES), "r4", "", "none"),

        /**
         * r4 with a staff directory over it: behind the membrane, its people
         * keyed by what the provider calls them, and its trail on.
         *
         * <p>The directory block is a tenant's declaration and cannot be a
         * flag a class sets, which is what kept this family in a world of its
         * own. The trail is on because every provisioning operation is a
         * disclosure and has to be answerable later as one.
         */
        R4_SCIM("sharedr4scim", """
                [{"name":"Person","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Practitioner","identity":"internal","handling":"operational"}]"""
                .formatted(STAFF_IDS), "r4",
                ",\"pdi\":true,\"scim\":{\"system\":\"" + STAFF_IDS + "\"}", "full"),

        /** r4 publishing a mirrored vocabulary, for the receiver below. */
        R4_MIRROR_SOURCE("sharedr4mirrorsource", """
                [{"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"CodeSystem","identity":"canonical","handling":"mirrored"}]""",
                "r4", "", "none"),

        /** r4 taking that vocabulary from the source above. */
        R4_MIRROR_RECEIVER("sharedr4mirrorreceiver", """
                [{"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"CodeSystem","identity":"canonical","handling":"mirrored"}]""",
                "r4", ",\"dependencies\":[{\"name\":\"sharedr4mirrorsource\","
                        + "\"types\":[\"CodeSystem\"]}]", "none"),

        /** r4 that manages other tenants: a partner, for the one below. */
        R4_PARTNER("sharedr4partner", """
                [{"name":"Basic","identity":"internal","handling":"operational"}]""",
                "r4", "", "none"),

        /**
         * r4 managed by the partner above, with its trail on.
         *
         * <p>The relation is declared when the tenant is created and is the
         * only thing that makes its work visible outside it, so it cannot be
         * something a class turns on afterwards — it is the shape.
         */
        R4_MANAGED("sharedr4managed", """
                [{"name":"Basic","identity":"internal","handling":"operational"}]""",
                "r4", ",\"managedBy\":\"sharedr4partner\"", "writes"),

        /** r4 publishing shapes, for the reader below to take them from. */
        R4_SHAPE_ZONE("sharedr4shapezone", """
                [{"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"}]""",
                "r4", "", "none"),

        /**
         * r4 taking its shapes from the zone above rather than authoring
         * them, which is what replicated says.
         */
        R4_SHAPE_READER("sharedr4shapereader", """
                [{"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                 {"name":"Observation","identity":"internal","handling":"operational"}]""",
                "r4", ",\"dependencies\":[{\"name\":\"sharedr4shapezone\","
                        + "\"types\":[\"StructureDefinition\"]}]", "none"),

        /**
         * r4 publishing a vocabulary and a clinical record side by side, for
         * the dependant below to take both from.
         */
        R4_GRAIN_UPSTREAM("sharedr4grainupstream", """
                [{"name":"CodeSystem","identity":"canonical","handling":"operational"},
                 {"name":"Encounter","identity":"internal","handling":"operational"}]""",
                "r4", "", "none"),

        /**
         * r4 taking both of those types from the upstream above.
         *
         * <p>A dependency names the tenant it is on, so this shape names that
         * one — which is why the two are declared together and why asking for
         * this one means asking for that one first.
         */
        R4_GRAIN_DEPENDANT("sharedr4graindependant", """
                [{"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                 {"name":"Encounter","identity":"internal","handling":"replicated"}]""",
                "r4", ",\"dependencies\":[{\"name\":\"sharedr4grainupstream\","
                        + "\"types\":[\"CodeSystem\",\"Encounter\"]}]", "none"),

        /**
         * r6, for what has to be served on the version after the one
         * everything else here uses.
         *
         * <p>A face binds one version, so this cannot be a flag on another
         * shape: it is a tenant of its own or it is nothing.
         */
        R6("sharedr6", """
                [{"name":"Patient","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"}]"""
                .formatted(EID), "r6", "", "none"),

        /**
         * r4 whose ValueSet has its envelope computed in the database.
         *
         * <p>A shape rather than a flag a class turns on, because where the
         * envelope is computed is part of what a type IS: it is read when the
         * type is mounted, so it cannot be switched on inside a tenant that
         * has already answered a search without it.
         */
        R4_DB_ENVELOPE("sharedr4dbenvelope", """
                [{"name":"ValueSet","identity":"canonical","handling":"operational",
                  "extractor":"database"},
                 {"name":"CodeSystem","identity":"canonical","handling":"operational"}]""",
                "r4", "", "none"),

        /**
         * r4 holding profiles of its own, and NOT a face root.
         *
         * <p>A tenant that authors StructureDefinitions and keeps ordinary
         * records beside them. It reads the version's definitions from the
         * face root already up beside it, which is the expensive half and is
         * shared — so a class wanting this pair now brings up one tenant
         * rather than two.
         */
        R4_PROFILED("sharedr4profiled", """
                [{"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                 {"name":"Patient","identity":"internal","handling":"operational"},
                 {"name":"Observation","identity":"internal","handling":"operational"}]""",
                "r4", "", "none"),

        /**
         * A face root: it holds the version's whole definition set as records,
         * which is the expensive thing in this suite and was being built four
         * times over. Patient declares the database as its verdict because one
         * class needs that and the others never write a Patient.
         */
        R4_FACE_ROOT("sharedr4faceroot", """
                [{"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                 {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                 {"name":"ValueSet","identity":"canonical","handling":"operational"},
                 {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                 {"name":"Patient","identity":"internal","handling":"operational",
                  "verdict":"database"},
                 {"name":"Observation","identity":"internal","handling":"operational"}]""",
                "r4", ",\"faceRoot\":true", "none");

        /**
         * The tenant code, spelled out rather than derived from the constant's
         * own name.
         *
         * <p>Derived read better and could not be read. The check that scans
         * this suite for two classes opening one tenant code resolves names
         * bound to literals; a code assembled at runtime from {@code name()}
         * left this class — the one class that hands the same tenant to
         * everybody — invisible to the scan that exists for exactly that
         * hazard. A literal is the same string and can be found by looking.
         */
        private final String code;
        private final String types;
        private final String face;
        /** What sits beside the types — the flags a shape turns on for itself. */
        private final String extras;
        /**
         * Its audit level. Full for the ordinary shapes, because a trail is
         * part of what they are for; none for a face root, which would
         * otherwise write an entry for every definition of the version it
         * holds and spend longer doing that than bringing the face up.
         */
        private final String audit;

        Shape(String code, String types, String face, String extras, String audit) {
            this.code = code;
            this.types = types;
            this.face = face;
            this.extras = extras;
            this.audit = audit;
        }

        /** The tenant code: shared means shared. */
        public String code() {
            return code;
        }
    }

    /** The identifier system the identifier-keyed shapes are declared against. */
    public static final String EID = "https://ee.ee/eid";

    /** What a participant declaration is keyed by, for the shape that holds them. */
    public static final String PARTICIPANTS = "https://shared.test/participant";

    /** And the organisation a declaration names, or that a grant is at. */
    public static final String ORGS = "https://shared.test/org";

    /** What a person signs in as, for the shapes that key people by login. */
    public static final String LOGINS = "https://shared.test/login";

    /** What the zone shape's projected type is keyed by. */
    public static final String BENCHES = "https://shared.test/benches";

    /** What an identity provider keys the people it provisions by. */
    public static final String STAFF_IDS = "urn:shared.test:idp:external-id";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** Keyed by tenant CODE rather than by shape, since a shape can have several. */
    private static final Map<String, Tenant> UP = new ConcurrentHashMap<>();
    /**
     * The provisioner the shared runtime was built on.
     *
     * <p>Held because a tenant's bootstrap credential is ITS deployment's,
     * and a class that authenticates as the deployment has to be able to
     * ask for it. Ten classes were building a runtime of their own for no
     * other reason than that this was unreachable.
     */
    private static LocalDatabasePerTenantProvisioner PROVISIONER;

    private static final TenantRuntimeManager MANAGER = start();
    private static Path directory;

    private SharedTenants() {
    }

    private static TenantRuntimeManager start() {
        try {
            directory = Files.createTempDirectory("dbo-shared-tenants");
            PROVISIONER = new LocalDatabasePerTenantProvisioner(
                    SharedPostgres.urlFor("sharedtenants"),
                    SharedPostgres.username(), SharedPostgres.password());
            byte[] kek = new byte[32];
            new java.security.SecureRandom().nextBytes(kek);
            TenantRuntimeManager manager = new TenantRuntimeManager(directory, PROVISIONER,
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
        return of(shape, 1);
    }

    /**
     * The {@code nth} tenant of this shape — a second and a third, for the
     * classes whose whole point is that two tenants cannot see each other.
     *
     * <p>Those classes were the ones a shared world seemed unable to serve:
     * they need several tenants AT ONCE, and a fixture handing out one per
     * shape has nothing to give them. Numbering is all that was missing. Two
     * classes asking for the same pair get the same pair, and what each proves
     * about isolation stays true, because each still asks only about the
     * records it wrote.
     *
     * <p>The discipline is the one sharing always asks for, and it bites
     * harder here: scope every assertion to what this class made, and give
     * anything claimed by identity — a step name, an identifier value — a name
     * of this class's own.
     */
    public static synchronized Tenant of(Shape shape, int nth) {
        if (nth < 1) {
            throw new IllegalArgumentException("there is no tenant before the first: " + nth);
        }
        String code = nth == 1 ? shape.code() : shape.code() + nth;
        // The local, not the lambda's parameter, and they are the same value:
        // computeIfAbsent hands the key back. The check that reads this suite
        // for two classes taking one tenant code resolves a literal, a field,
        // a local or an argument at the call site, and a parameter bound
        // somewhere it cannot follow reads as a spec written under no name it
        // knows — so this class would drop out of the scan that exists to
        // notice exactly the collision this class makes easy.
        return UP.computeIfAbsent(code, unused -> {
            try {
                Files.writeString(directory.resolve(code + ".json"), """
                        {"code":"%s","face":"%s"%s,"audit":{"level":"%s"},"types":%s}"""
                        .formatted(code, shape.face, shape.extras, shape.audit, shape.types));
                UntilServed.scan(MANAGER, code);
                return new Tenant(code);
            } catch (Exception e) {
                throw new IllegalStateException("shared tenant " + code + " did not come up", e);
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

        /** The facade a face serves through, for the classes that drive it directly. */
        public cloud.jengu.dbo.fhir.common.FhirStoreFacade store() {
            return MANAGER.runtime(code).orElseThrow().store();
        }

        /**
         * The URL of this tenant's own database.
         *
         * <p>For the few classes that look at what was actually stored. It is
         * derived from the shared provisioner's URL rather than from a class
         * name, which is what a converted class would otherwise keep pointing
         * at — a database belonging to the world it no longer builds.
         */
        public String databaseUrl() {
            return SharedPostgres.urlFor("sharedtenants")
                    .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + code.replace('-', '_'));
        }

        public ChangeFeed feed() {
            return MANAGER.runtime(code).orElseThrow().feed();
        }

        /** The other feed: what the face gave this tenant, apart from its records. */
        public ChangeFeed definitionsFeed() {
            return MANAGER.runtime(code).orElseThrow().definitionsFeed();
        }

        /**
         * The secret this deployment holds for this tenant's bootstrap client.
         *
         * <p>The same thing a deployment keeps in its own vault: the credential
         * that exists before anything the tenant itself could have issued.
         */
        public String bootstrapSecret() {
            return PROVISIONER.bootstrapClientSecret(code);
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
                return Extracted.tokenIn(issued.body());
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
