package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-TENANT-OPENING, walked on the sample world.
 *
 * <p>Ines builds the platform a clinic group runs on. She does not run a
 * database team, she does not want a second identity system, and she is not
 * going to write a tenant boundary of her own. This is her first hour: the
 * store is already running inside her application, a clinic becomes a tenant
 * with a database and an authority of its own, her identity provider fills the
 * staff directory, and a clinician's reach is a record rather than code.
 *
 * <p><b>Its clinics are its own, because opening one is the story.</b> They
 * are declared on the running deployment the way a host declares any tenant,
 * named for this story and run, and withdrawn when it ends. Nothing here is
 * about the world's other members, and nothing counts them.
 */
@AUserStory
class ATenantOpensAndItsPeopleGetInIT {

    private final StoryNames names = StoryNames.of(DboStories.TENANT_OPENING);

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @Autowired
    org.springframework.core.env.Environment environment;

    /** The clinic Ines is onboarding. */
    private String clinic;
    /** A second one, opened later from the same running store. */
    private String second;
    /** The identity provider's namespace for staff numbers. */
    private String idp;

    private String directoryToken;
    private String personId;

    @BeforeAll
    void namesForTheClinics() {
        clinic = names.tenant("clinic");
        second = names.tenant("second");
        idp = "urn:" + names.prefix() + ":" + names.run() + ":idp";
    }

    @AfterAll
    void theClinicsAreWithdrawn() {
        if (clinic != null) {
            dbo.retract(clinic);
            dbo.retract(second);
        }
    }

    private String staffTypes() {
        return """
                [{"name":"Person","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Practitioner","identity":"internal","handling":"operational"},
                 {"name":"Patient","identity":"internal","handling":"operational"}]"""
                .formatted(idp);
    }

    // ── the store arrives as a library, and a clinic becomes a tenant ──

    @Test
    @Order(1)
    @DisplayName("a declaration is the whole of opening a clinic: the store is already running "
            + "in the application, and the tenant's services appear when its declaration does")
    @Proving({DboPromises.CONT_EMBEDDED_IN_JVM, DboPromises.CONT_DYNAMIC_TENANT_SERVICES,
            DboPromises.TEN_DEDICATED_DATABASE_TIER})
    void aDeclarationIsTheWholeOfOpeningAClinic() {
        assertFalse(dbo.serving().contains(clinic), "the clinic is not served before it exists");

        // pdi:true because the staff directory needs it — a tenant that lets
        // an identity provider write people is a tenant holding identifying
        // data, and the store refuses the combination by name rather than
        // serving a directory outside the membrane.
        dbo.declare(clinic, """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"full"},
                 "scim":{"system":"%s"},
                 "types":%s}""".formatted(clinic, idp, staffTypes()));

        Proves.that(DboPromises.CONT_DYNAMIC_TENANT_SERVICES,
                dbo.until(clinic, true, Duration.ofMinutes(10)),
                "the declaration alone did not bring the clinic up: " + dbo.serving());
        Proves.that(DboPromises.TEN_DEDICATED_DATABASE_TIER,
                tenants.store(clinic).isPresent(),
                "the clinic is served and has no store of its own");
        Proves.that(DboPromises.CONT_EMBEDDED_IN_JVM,
                dbo.capability(clinic).serves("Patient"),
                "the store inside this application does not answer for the clinic it opened");
    }

    // ── nothing is reachable until the tenant's own authority says so ──

    @Test
    @Order(2)
    @DisplayName("the clinic's surface answers nobody without a credential, and says so as a "
            + "refusal rather than as an absence")
    @Proving(DboPromises.AUTH_DENY_BY_DEFAULT)
    void nothingIsReachableWithoutACredential() {
        HttpResponse<String> anonymous = dbo.get(fhir(clinic) + "/Patient?_summary=count", null);

        Proves.that(DboPromises.AUTH_DENY_BY_DEFAULT, anonymous.statusCode() == 401,
                "401 and not 404: the surface is mounted and guarded, which is the "
                        + "distinction between booting and serving — "
                        + anonymous.statusCode() + " " + anonymous.body());
    }

    @Test
    @Order(3)
    @DisplayName("the token comes from the clinic's own issuer and is checked without asking "
            + "anybody, and its scope is the whole of what it reaches")
    @Proving({DboPromises.AUTH_TENANT_SCOPED_ISSUER, DboPromises.AUTH_BEARER_LOCAL_VALIDATION,
            DboPromises.AUTH_SMART_SHAPED_SCOPES})
    void theClinicsOwnAuthorityIssuesAndChecksTheToken() {
        authority(clinic).ensureClient(app(), "app-secret", List.of("system/Patient.read"));
        String reads = token(clinic, app(), "app-secret");

        assertEquals(200, statusOf(fhir(clinic) + "/Patient?_summary=count", reads),
                "the scope it was granted answers");
        Proves.that(DboPromises.AUTH_SMART_SHAPED_SCOPES,
                statusOf(fhir(clinic) + "/Practitioner?_summary=count", reads) == 403,
                "a type it was not granted was not refused");
    }

    @Test
    @Order(4)
    @DisplayName("a second clinic opens from the same running store, and the first one's "
            + "credential is not merely refused there — it is unintelligible")
    @Proving({DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS, DboPromises.TEN_STRUCTURAL_SCOPING})
    void aSecondClinicOpensAndTheTenantsCannotSeeEachOther() {
        dbo.declare(second, """
                {"code":"%s","face":"r4","audit":{"level":"full"},
                 "types":%s}""".formatted(second, organisationTypes()));
        assertTrue(dbo.until(second, true, Duration.ofMinutes(10)),
                "the second clinic did not come up: " + dbo.serving());

        String first = token(clinic, app(), "app-secret");
        assertEquals(200, statusOf(fhir(clinic) + "/Patient?_summary=count", first));
        Proves.that(DboPromises.TEN_STRUCTURAL_SCOPING,
                statusOf(fhir(second) + "/Patient?_summary=count", first) == 401,
                "401, not 403: the second clinic's authority has never heard of that issuer, "
                        + "so the token is wrong the way noise is wrong. A tenant is a store, "
                        + "not a filter over a shared one");
        Proves.that(DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS,
                authority(clinic) != authority(second),
                "the two clinics share one authority rather than each opening its own");
    }

    // ── the identity provider fills the staff directory ──

    @Test
    @Order(5)
    @DisplayName("the identity provider provisions a clinician, and what lands is the person "
            + "with a practitioner capacity linked to them")
    @Proving({DboPromises.SCIM_DECLARED_PER_TENANT, DboPromises.SCIM_DIRECTORY_CREDENTIAL,
            DboPromises.SCIM_USER_IS_THE_PERSON})
    void theDirectoryProvisionsAClinician() {
        authority(clinic).ensureClient(names.value("idp"), "idp-secret", List.of("scim"));
        directoryToken = token(clinic, names.value("idp"), "idp-secret");

        HttpResponse<String> created = scim("POST", "/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"emp-4711","userName":"maarja@kevadkliinik.ee",
                 "name":{"familyName":"Kask","givenName":"Maarja"},"active":true}""");
        assertEquals(201, created.statusCode(), created.body());
        personId = dbo.says(created).one("id").orElseThrow();

        String person = new String(tenants.store(clinic).orElseThrow()
                .get("Person", personId).orElseThrow().payload(), StandardCharsets.UTF_8);
        Proves.that(DboPromises.SCIM_USER_IS_THE_PERSON, person.contains("Practitioner/"),
                "the person is who they are and the practitioner a capacity they act in, "
                        + "ensured and linked on create: " + person);
    }

    @Test
    @Order(6)
    @DisplayName("the directory enumerates its own users, and that enumeration never becomes "
            + "a capability on the clinical surface")
    @Proving({DboPromises.SCIM_ENUMERATION_STAYS_INSIDE, DboPromises.SCIM_GROUPS_READ_ONLY})
    void enumerationStaysBehindTheDirectoryDoor() {
        HttpResponse<String> listed = scim("GET", "/Users", null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains("\"totalResults\":1"), listed.body());

        Proves.that(DboPromises.SCIM_GROUPS_READ_ONLY,
                scim("POST", "/Groups", "{}").statusCode() == 405,
                "who works here is the identity provider's to say; who is an admin here is "
                        + "not, so groups read and never write");

        // The directory credential carries scim and nothing else, so the
        // enumeration it needs is not a door onto the clinical record.
        Proves.that(DboPromises.SCIM_ENUMERATION_STAYS_INSIDE,
                statusOf(fhir(clinic) + "/Patient?_summary=count", directoryToken) == 403,
                "the directory credential reached the clinical surface");
    }

    // ── and what each of them may do is a record, not code ──

    @Test
    @Order(7)
    @DisplayName("a clinician's reach is declared as a grant rather than coded, and the human "
            + "it attaches to is an ordinary record in the clinic's own store")
    @Proving({DboPromises.AUTH_IDENTITY_AS_RECORDS,
            DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL})
    void whatAClinicianMayDoIsDeclaredAndWhoTheyAreIsARecord() {
        // Declaring the same grant twice is the ordinary case, not an error:
        // configuration arrives from wherever the clinic keeps it, and a
        // bring-up that refused a grant it already had would make every
        // redeploy a migration.
        authority(clinic).ensureRoleGrant("clinician",
                List.of("system/Patient.read", "system/Patient.write"));
        authority(clinic).ensureRoleGrant("clinician",
                List.of("system/Patient.read", "system/Patient.write"));

        Proves.that(DboPromises.AUTH_IDENTITY_AS_RECORDS,
                tenants.store(clinic).orElseThrow().get("Person", personId).isPresent(),
                "the human the grant will attach to is not an ordinary record in the clinic's "
                        + "own store");
    }

    // ── a role is a relation the clinic declares, and it can be taken back ──

    @Test
    @Order(8)
    @DisplayName("at the second clinic, a role naming its practitioner and its organisation "
            + "grants that organisation's scopes and reaches nowhere else, whether it names "
            + "them by identity or by id")
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void aRoleIsScopedToTheOrganisationItNames() {
        ATenantsDoor door = new ATenantsDoor(dbo, second);
        var lab = dbo.write(second, "Organization", """
                {"resourceType":"Organization","identifier":[{"system":"%s","value":"main-lab"}],
                 "name":"Main Lab"}""".formatted(orgs()));
        assertTrue(lab.accepted(), lab.body());
        String organisation = lab.idOrFail();
        authority(second).ensureRoleGrant("lab-tech", List.of("user/*.read"));
        authority(second).ensureRoleGrant("lab-tech", "main-lab",
                List.of("user/*.read", "user/Observation.write"));

        // By identity: the role names who and where by what the clinic's other
        // systems call them.
        String byIdentity = aClinician(second, "albus", null);
        assertEquals(201, door.post("/PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"identifier":{"system":"%s","value":"albus"}},
                 "organization":{"identifier":{"system":"%s","value":"main-lab"}},
                 "code":[{"coding":[{"system":"urn:example:role","code":"lab-tech"}]}]}"""
                .formatted(logins(), orgs())).statusCode());
        var granted = authority(second).evaluateGrants(byIdentity);
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                granted.scopes().contains("user/Observation.write")
                        && granted.organisations().size() == 1
                        && granted.organisations().contains(organisation),
                "the role did not grant the organisation's scopes, or reached beyond the "
                        + "organisation it names, which is the failure that looks like "
                        + "success: " + granted);

        // By id: the same relation, and no second class of it.
        String severus = practitioner(second, "severus");
        String byId = person(second, "severus", severus);
        assertEquals(201, door.post("/PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "organization":{"reference":"Organization/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"lab-tech"}]}]}"""
                .formatted(severus, organisation)).statusCode());
        var same = authority(second).evaluateGrants(byId);
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                same.scopes().contains("user/Observation.write")
                        && same.organisations().contains(organisation),
                "a role naming them by id granted something other than one naming them by "
                        + "identity: " + same);
    }

    @Test
    @Order(9)
    @DisplayName("withdrawing a role stops it granting at the next token, withdrawing it "
            + "again is a true answer, and the withdrawn grant stays answerable")
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void aGrantCanBeTakenBack() throws Exception {
        authority(second).ensureRoleGrant("laborant", List.of("user/Specimen.read"));
        String minerva = aClinician(second, "minerva", "laborant");
        assertTrue(authority(second).evaluateGrants(minerva).scopes()
                        .contains("user/Specimen.read"),
                "the role granted nothing to begin with, so withdrawing it proves nothing");

        assertTrue(authority(second).withdrawRoleGrant("laborant"),
                "there was an active grant and the withdrawal says there was not");
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                !authority(second).evaluateGrants(minerva).scopes().contains("user/Specimen.read")
                        && !authority(second).activeRoleCodes().contains("laborant"),
                "the role still grants what it granted, so an operator who removed it from "
                        + "configuration is told it is gone and it is not");
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                !authority(second).withdrawRoleGrant("laborant")
                        && !authority(second).withdrawRoleGrant("never-granted"),
                "withdrawing what is not granted said it had just taken something away, and "
                        + "the client that declares grants re-runs on every boot");

        // Read the way an operator reads it: from the clinic's own database,
        // where the grant is a record in the identity domain.
        String grant = null;
        try (var c = java.sql.DriverManager.getConnection(tenantDatabase(second),
                        environment.getRequiredProperty("dbo.admin.user"),
                        environment.getRequiredProperty("dbo.admin.password"));
                var ps = c.prepareStatement("SELECT convert_from(payload, 'UTF8') FROM "
                        + cloud.jengu.dbo.core.api.Domains.tables(
                                cloud.jengu.dbo.auth.IdentityModel.DOMAIN)
                        + "_data WHERE type = 'RoleGrant' AND NOT deleted");
                var rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getString(1).contains("\"laborant\"")) {
                    grant = rs.getString(1);
                }
            }
        }
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                grant != null && grant.contains("withdrawnAt")
                        && grant.contains("user/Specimen.read"),
                "the grant was deleted rather than withdrawn, so nobody can answer when the "
                        + "role stopped and what it could do while it lasted: " + grant);
    }

    // ── and the directory keeps the staff list, to the end of somebody's time there ──

    @Test
    @Order(11)
    @DisplayName("the directory answers the two exact questions it is asked, refuses any "
            + "other, and refuses a second person claiming the same external id")
    @Proving(DboPromises.SCIM_ENUMERATION_STAYS_INSIDE)
    void theDirectoryAnswersExactlyAndRefusesTheRest() {
        Proves.that(DboPromises.SCIM_ENUMERATION_STAYS_INSIDE,
                scim("GET", "/Users?filter=" + encoded("externalId eq \"emp-4711\""), null)
                        .body().contains(personId)
                        && scim("GET", "/Users?filter="
                                + encoded("userName eq \"maarja@kevadkliinik.ee\""), null)
                        .body().contains(personId),
                "the directory did not answer the two exact filters it supports");
        assertEquals(400, scim("GET", "/Users?filter=" + encoded("name.familyName co \"Ka\""),
                        null).statusCode(),
                "anything beyond the two exact filters is refused, never half-answered");
        assertEquals(409, scim("POST", "/Users", """
                {"externalId":"emp-4711","userName":"second@kevadkliinik.ee"}""").statusCode(),
                "a second person claiming the same external id was not refused as a conflict");

        // And the store's own door keeps refusing the shape an enumeration takes.
        HttpResponse<String> swept = dbo.get(fhir(clinic) + "/Person?identifier="
                + encoded(idp + "|"), dbo.token(clinic));
        Proves.that(DboPromises.SCIM_ENUMERATION_STAYS_INSIDE, swept.statusCode() >= 400,
                "an enumeration-shaped search at the front door answered: "
                        + swept.statusCode() + " " + swept.body());
    }

    @Test
    @Order(12)
    @DisplayName("a replace against a stale version is refused, and deactivating the person "
            + "deactivates the capacity they acted in, which is a state and never an erasure")
    @Proving(DboPromises.SCIM_DEPROVISION_IS_A_STATE)
    void deprovisioningIsAState() {
        HttpResponse<String> current = scim("GET", "/Users/" + personId, null);
        String version = dbo.says(current).one("meta.version").orElseThrow(
                () -> new AssertionError("the user carries no version: " + current.body()));

        assertEquals(412, scimWithHeader("PUT", "/Users/" + personId, """
                {"externalId":"emp-4711","userName":"maarja@kevadkliinik.ee","active":true}""",
                "If-Match", "W/\"99\"").statusCode(),
                "a replace made against a version that has moved was accepted");

        HttpResponse<String> replaced = scimWithHeader("PUT", "/Users/" + personId, """
                {"externalId":"emp-4711","userName":"maarja@kevadkliinik.ee",
                 "name":{"familyName":"Kask","givenName":"Maarja"},"active":false}""",
                "If-Match", version);
        assertEquals(200, replaced.statusCode(), replaced.body());

        var store = tenants.store(clinic).orElseThrow();
        String person = new String(store.get("Person", personId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        String practitioner = person.replaceAll("(?s).*Practitioner/([0-9a-f-]+).*", "$1");
        Proves.that(DboPromises.SCIM_DEPROVISION_IS_A_STATE,
                new String(store.get("Practitioner", practitioner).orElseThrow().payload(),
                        StandardCharsets.UTF_8).contains("\"active\":false"),
                "deactivating the person did not reach the capacity they acted in");
        Proves.that(DboPromises.SCIM_DEPROVISION_IS_A_STATE,
                scim("DELETE", "/Users/" + personId, null).statusCode() == 405,
                "the directory erased somebody, and erasure is not a provisioning operation");
    }

    @Test
    @Order(13)
    @DisplayName("the directory's credential and the store's do not cross, every directory "
            + "operation is a disclosure in the trail, and its groups are the clinic's grants")
    @Proving({DboPromises.SCIM_DIRECTORY_CREDENTIAL, DboPromises.SCIM_EVERY_OP_IS_A_DISCLOSURE,
            DboPromises.SCIM_GROUPS_READ_ONLY})
    void theDirectoryIsItsOwnDoor() {
        Proves.that(DboPromises.SCIM_DIRECTORY_CREDENTIAL,
                dbo.send(java.net.http.HttpRequest.newBuilder(
                                URI.create(dbo.at(clinic) + "/scim/v2/Users")).GET(),
                        dbo.token(clinic)).statusCode() == 403,
                "a store credential was accepted as a directory credential");

        HttpResponse<String> trail = dbo.get(fhir(clinic) + "/AuditEvent?agent="
                + names.value("idp"), dbo.token(clinic));
        Proves.that(DboPromises.SCIM_EVERY_OP_IS_A_DISCLOSURE,
                trail.body().contains("SYSADMIN"),
                "the directory's operations are not in the trail as disclosures with their "
                        + "purpose, so who looked at the staff list is unanswerable: "
                        + trail.body());

        HttpResponse<String> groups = scim("GET", "/Groups", null);
        Proves.that(DboPromises.SCIM_GROUPS_READ_ONLY,
                groups.statusCode() == 200 && groups.body().contains("clinician"),
                "the groups are not rendered from the clinic's grants: " + groups.body());
    }

    @Test
    @Order(14)
    @DisplayName("a clinic that declares no directory has no directory endpoints at all")
    @Proving(DboPromises.SCIM_DECLARED_PER_TENANT)
    void noDeclarationNoDirectory() {
        HttpResponse<String> absent = dbo.send(java.net.http.HttpRequest.newBuilder(
                        URI.create(dbo.at(second) + "/scim/v2/Users")).GET(),
                dbo.token(second));
        Proves.that(DboPromises.SCIM_DECLARED_PER_TENANT, absent.statusCode() == 404,
                "a clinic that declared no directory answered at its endpoints: "
                        + absent.statusCode());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> scimWithHeader(String method, String path, String body,
            String header, String value) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(dbo.at(clinic) + "/scim/v2" + path))
                .header("Content-Type", "application/scim+json")
                .header(header, value)
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return dbo.send(request, directoryToken);
    }

    private String orgs() {
        return "urn:" + names.prefix() + ":" + names.run() + ":org";
    }

    private String logins() {
        return "urn:" + names.prefix() + ":" + names.run() + ":login";
    }

    private String organisationTypes() {
        return """
                [{"name":"Organization","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Practitioner","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"Person","identity":"identifier","systems":["%s"],
                  "handling":"operational"},
                 {"name":"PractitionerRole","identity":"internal","handling":"operational"},
                 {"name":"Patient","identity":"internal","handling":"operational"}]"""
                .formatted(orgs(), logins(), logins());
    }

    /** A practitioner, the person who is one, and optionally a role; the person's id. */
    private String aClinician(String tenant, String login, String role) {
        String practitioner = practitioner(tenant, login);
        String person = person(tenant, login, practitioner);
        if (role != null) {
            assertEquals(201, new ATenantsDoor(dbo, tenant).post("/PractitionerRole", """
                    {"resourceType":"PractitionerRole",
                     "practitioner":{"reference":"Practitioner/%s"},
                     "code":[{"coding":[{"system":"urn:example:role","code":"%s"}]}]}"""
                    .formatted(practitioner, role)).statusCode());
        }
        return person;
    }

    private String practitioner(String tenant, String login) {
        var written = dbo.write(tenant, "Practitioner", """
                {"resourceType":"Practitioner","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}""".formatted(logins(), login, login));
        assertTrue(written.accepted(), written.body());
        return written.idOrFail();
    }

    private String person(String tenant, String login, String practitioner) {
        var written = dbo.write(tenant, "Person", """
                {"resourceType":"Person","identifier":[{"system":"%s","value":"%s"}],
                 "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                .formatted(logins(), login, practitioner));
        assertTrue(written.accepted(), written.body());
        return written.idOrFail();
    }

    /** The clinic's own database, on the server the deployment's admin connection names. */
    private String tenantDatabase(String tenant) {
        String admin = environment.getRequiredProperty("dbo.admin.jdbc-url");
        return admin.substring(0, admin.lastIndexOf('/') + 1) + "tenant_"
                + tenant.replace('-', '_');
    }


    private String app() {
        return names.value("app");
    }

    private TenantAuthority authority(String tenant) {
        return tenants.authority(tenant).orElseThrow(
                () -> new AssertionError(tenant + " has no authority: " + dbo.serving()));
    }

    private String fhir(String tenant) {
        return dbo.at(tenant) + "/fhir";
    }

    private int statusOf(String url, String bearer) {
        return dbo.get(url, bearer).statusCode();
    }

    private HttpResponse<String> scim(String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(dbo.at(clinic) + "/scim/v2" + path))
                .header("Content-Type", "application/scim+json");
        request = body == null
                ? request.method(method, HttpRequest.BodyPublishers.noBody())
                : request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return dbo.send(request, directoryToken);
    }

    /** A token from the clinic's own issuer, asked for the way any client asks. */
    private String token(String tenant, String clientId, String secret) {
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        HttpResponse<String> issued = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(tenant) + "/oidc/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), null);
        assertEquals(200, issued.statusCode(), issued.body());
        return dbo.says(issued).one("access_token").orElseThrow(
                () -> new AssertionError("no token in " + issued.body()));
    }
}
