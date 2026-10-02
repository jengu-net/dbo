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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-TENANT-OPENING, walked in Rowling Land, the sample world.
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

    /** What the clinic's application declares in a clinic as it opens. */
    @Autowired
    cloud.jengu.dbo.samples.server.OpeningAClinic opening;

    /** The clinic Ines is onboarding. */
    private String clinic;
    /** A second one, opened later from the same running store. */
    private String second;
    /** The identity provider's namespace for staff numbers. */
    private String idp;

    private String directoryToken;
    private String personId;
    /** Albus, as the second clinic knows him: a person holding a role there. */
    private String albus;

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
        // --8<-- [start:declare]
        dbo.declare(clinic, """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"full"},
                 "scim":{"system":"%s"},
                 "types":%s}""".formatted(clinic, idp, staffTypes()));
        // --8<-- [end:declare]

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
        // The clinic's application registered itself as the clinic opened,
        // with the one scope its screens need; it signs in as any client does.
        // Opening finishes a moment after the clinic first answers, so the
        // first sign-in is waited for rather than assumed.
        String reads = untilSignedIn(clinic, app(), appSecret());

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

        String first = token(clinic, app(), appSecret());
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
        // The clinic's application declared the role as the clinic opened.
        // Declaring it again is the ordinary case, not an error: every start
        // declares it, and a bring-up that refused a grant it already had
        // would make every redeploy a migration.
        assertTrue(authority(clinic).activeRoleCodes()
                        .contains(cloud.jengu.dbo.samples.server.OpeningAClinic.CLINICIAN),
                "the clinic opened without the role its application declares: "
                        + authority(clinic).activeRoleCodes());
        assertTrue(opening.declare(clinic), "the clinic has no authority to declare it with");
        assertTrue(authority(clinic).activeRoleCodes()
                        .contains(cloud.jengu.dbo.samples.server.OpeningAClinic.CLINICIAN),
                "declaring the role again took it away");

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
        albus = byIdentity;
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
        String grant = new WhatTheDatabaseHolds(environment, second).rows(
                        "SELECT convert_from(payload, 'UTF8') FROM "
                        + cloud.jengu.dbo.core.api.Domains.tables(
                                cloud.jengu.dbo.auth.IdentityModel.DOMAIN)
                        + "_data WHERE type = 'RoleGrant' AND NOT deleted").stream()
                .filter(row -> row.contains("\"laborant\"")).reduce((first, last) -> last)
                .orElse(null);
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                grant != null && grant.contains("withdrawnAt")
                        && grant.contains("user/Specimen.read"),
                "the grant was deleted rather than withdrawn, so nobody can answer when the "
                        + "role stopped and what it could do while it lasted: " + grant);
    }

    @Test
    @Order(10)
    @DisplayName("an identifier from outside names the person and not the capacity they act "
            + "in, inside the membrane exactly as outside it")
    @Proving(DboPromises.AUTH_FEDERATED_HUMANS)
    void anIdentifierFromOutsideNamesThePerson() {
        Proves.that(DboPromises.AUTH_FEDERATED_HUMANS,
                authority(second).resolveByNationalId(logins(), "albus")
                        .filter(albus::equals).isPresent(),
                "the identifier did not resolve to the person who holds the role");
        // The first clinic keeps its people behind the membrane, so the
        // identifier is matched through the vault's index without being shown
        // to the matching.
        Proves.that(DboPromises.AUTH_FEDERATED_HUMANS,
                authority(clinic).resolveByNationalId(idp, "emp-4711")
                        .filter(personId::equals).isPresent(),
                "the identifier did not resolve through the vault to the person the "
                        + "directory provisioned");
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

    // ── a clinician signs in, and a credential is theirs to change ──

    @Test
    @Order(15)
    @DisplayName("a clinician signs in through the front door with proof of the code they "
            + "asked for, and the token names a pseudonym and the role, never the human")
    @Proving({DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL, DboPromises.AUTH_PSEUDONYMOUS_TOKENS})
    void aClinicianSignsInAndTheTokenIsAPseudonym() throws Exception {
        authority(second).ensureRoleGrant("healer", List.of("user/*.read", "user/Patient.write"));
        healerPractitioner = practitioner(second, "hermione");
        healer = person(second, "hermione", healerPractitioner);
        healerRole = new ATenantsDoor(dbo, second).post("/PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"healer"}]}]}"""
                .formatted(healerPractitioner));
        assertEquals(201, healerRole.statusCode(), healerRole.body());
        authority(second).ensureLocalCredential("hermione", "granger9", healer);
        authority(second).ensureClient(webApp(), null, List.of("user/*.read", "user/*.write"),
                "public-pkce", List.of(REDIRECT));
        authority(second).ensureClient(portal(), PORTAL_SECRET,
                List.of("user/*.read", "user/*.write"), "confidential", List.of(REDIRECT));

        String verifier = verifier();
        HttpResponse<String> form = dbo.send(HttpRequest.newBuilder(URI.create(oidc(second)
                + "/authorize?response_type=code&client_id=" + webApp() + "&state=xyz"
                + "&redirect_uri=" + encoded(REDIRECT) + "&code_challenge=" + challenge(verifier)
                + "&code_challenge_method=S256")).GET(), null);
        assertEquals(200, form.statusCode(), form.body());
        HttpResponse<String> login = formPost(oidc(second) + "/authorize/login",
                "client_id=" + webApp() + "&redirect_uri=" + encoded(REDIRECT)
                        + "&state=xyz&code_challenge=" + challenge(verifier)
                        + "&login=hermione&password=granger9");
        assertEquals(302, login.statusCode(), login.body());
        HttpResponse<String> tokens = formPost(oidc(second) + "/token",
                "grant_type=authorization_code&client_id=" + webApp() + "&code="
                        + codeIn(login) + "&redirect_uri=" + encoded(REDIRECT)
                        + "&code_verifier=" + verifier);
        assertEquals(200, tokens.statusCode(), tokens.body());
        healerToken = dbo.says(tokens).one("access_token").orElseThrow();
        healerRefresh = dbo.says(tokens).one("refresh_token").orElseThrow();

        String claims = claimsOf(healerToken);
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                claims.contains("\"fhirUser\":\"Practitioner/" + healerPractitioner + "\"")
                        && claims.contains("\"roles\":[\"healer\"]")
                        && claims.contains("user/Patient.write"),
                "the token does not carry what the role record grants: " + claims);
        // The record ids are the pseudonym; the login, which is also the
        // practitioner's name and identifier here, is the human.
        Proves.that(DboPromises.AUTH_PSEUDONYMOUS_TOKENS, !claims.contains("hermione"),
                "the token names the human: " + claims);
    }

    @Test
    @Order(16)
    @DisplayName("the role's scopes are the whole of the clinician's reach, the trail names "
            + "the pseudonym, and a code is refused to anybody without its proof")
    @Proving({DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL, DboPromises.AUTH_SMART_SHAPED_SCOPES})
    void theRoleIsTheReachAndTheCodeNeedsItsProof() throws Exception {
        ATenantsDoor door = new ATenantsDoor(dbo, second);
        assertEquals(200, statusOf(fhir(second) + "/Patient?_summary=count", healerToken));
        assertEquals(201, door.postAs("/Patient", "{\"resourceType\":\"Patient\"}",
                healerToken).statusCode());
        Proves.that(DboPromises.AUTH_SMART_SHAPED_SCOPES,
                door.postAs("/Organization", """
                        {"resourceType":"Organization",
                         "identifier":[{"system":"%s","value":"healers-own"}]}"""
                        .formatted(orgs()), healerToken).statusCode() == 403,
                "the healer wrote a type their role does not grant");
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                dbo.get(fhir(second) + "/AuditEvent?action=C", dbo.token(second)).body()
                        .contains("Practitioner/" + healerPractitioner),
                "the trail does not name who wrote, by the pseudonym they act under");

        HttpResponse<String> elsewhere = dbo.send(HttpRequest.newBuilder(URI.create(
                oidc(second) + "/authorize?response_type=code&client_id=" + webApp()
                        + "&redirect_uri=" + encoded("http://evil.example/cb")
                        + "&code_challenge=x&code_challenge_method=S256")).GET(), null);
        assertEquals(400, elsewhere.statusCode(), "an unregistered redirect was not refused");
        assertTrue(elsewhere.headers().firstValue("Location").isEmpty(),
                "the refusal redirected to where it was asked to");
        HttpResponse<String> login = formPost(oidc(second) + "/authorize/login",
                "client_id=" + webApp() + "&redirect_uri=" + encoded(REDIRECT)
                        + "&code_challenge=" + challenge(verifier())
                        + "&login=hermione&password=granger9");
        HttpResponse<String> exchange = formPost(oidc(second) + "/token",
                "grant_type=authorization_code&client_id=" + webApp() + "&code="
                        + codeIn(login) + "&redirect_uri=" + encoded(REDIRECT)
                        + "&code_verifier=not-the-verifier");
        assertEquals(400, exchange.statusCode(), exchange.body());
        assertTrue(exchange.body().contains("invalid_grant"), exchange.body());
    }

    @Test
    @Order(17)
    @DisplayName("ending the role's period on the clinical record is what revokes, and the "
            + "next refresh is refused for it")
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void endingTheRolePeriodRevokes() {
        String role = dbo.says(healerRole).one("id").orElseThrow();
        HttpResponse<String> ended = new ATenantsDoor(dbo, second).put("/PractitionerRole/" + role,
                """
                {"resourceType":"PractitionerRole","id":"%s",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"healer"}]}],
                 "period":{"end":"%s"}}""".formatted(role, healerPractitioner,
                        java.time.LocalDate.now().minusDays(1)),
                "If-Match", "W/\"1\"");
        assertEquals(200, ended.statusCode(), ended.body());

        HttpResponse<String> refused = formPost(oidc(second) + "/token",
                "grant_type=refresh_token&refresh_token=" + encoded(healerRefresh));
        Proves.that(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL,
                refused.statusCode() == 400 && refused.body().contains("access_denied"),
                "a role whose period has ended still refreshed: " + refused.body());

        // The role again, for what the clinician does next.
        assertEquals(201, new ATenantsDoor(dbo, second).post("/PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"healer"}]}]}"""
                .formatted(healerPractitioner)).statusCode());
    }

    @Test
    @Order(18)
    @DisplayName("a confidential application gets an identity token addressed to it, and the "
            + "provisioning surface answers only the clinic's own machine credential")
    void theClinicsApplicationAndItsProvisioning() {
        String nonce = names.value("nonce");
        HttpResponse<String> tokens = signIn("hermione", "granger9", nonce);
        assertEquals(200, tokens.statusCode(), tokens.body());
        String id = claimsOf(dbo.says(tokens).one("id_token").orElseThrow(
                () -> new AssertionError("no identity token: " + tokens.body())));
        assertTrue(id.contains("\"aud\":\"" + portal() + "\"")
                && id.contains("\"nonce\":\"" + nonce + "\"")
                && id.contains("\"roles\":[\"healer\"]")
                && id.contains("\"fhirUser\":\"Practitioner/" + healerPractitioner + "\""), id);

        assertEquals(200, admin("/role-grants",
                "{\"role\":\"matron\",\"scopes\":[\"user/*.read\"]}", dbo.token(second)));
        assertEquals(200, admin("/credentials", "{\"login\":\"poppy\",\"secret\":\"pomfrey8\","
                + "\"personId\":\"" + healer + "\"}", dbo.token(second)));
        assertTrue(signsIn("poppy", "pomfrey8"), "the provisioned credential does not sign in");
        assertEquals(401, admin("/role-grants",
                "{\"role\":\"x\",\"scopes\":[\"user/*.read\"]}", null));
        assertEquals(403, admin("/role-grants",
                "{\"role\":\"x\",\"scopes\":[\"user/*.read\"]}", accessFor("hermione",
                        "granger9")), "a human's token provisioned grants");

        // An appliance is given the secret its operator holds, and approving it
        // again leaves that secret working; a client with no secret is refused,
        // since this store mints none.
        String appliance = names.value("edge");
        String secret = names.value("edge-secret");
        String client = "{\"client_id\":\"" + appliance + "\",\"secret\":\"" + secret
                + "\",\"scope\":[\"system/*.read\"]}";
        assertEquals(200, admin("/clients", client, dbo.token(second)));
        assertEquals(200, admin("/clients", client, dbo.token(second)));
        token(second, appliance, secret);
        HttpResponse<String> unminted = adminResponse("/clients",
                "{\"client_id\":\"" + names.value("no-secret") + "\",\"scope\":[]}",
                dbo.token(second));
        assertEquals(400, unminted.statusCode(), unminted.body());
        assertTrue(unminted.body().contains("does not mint"), unminted.body());
    }

    @Test
    @Order(19)
    @DisplayName("a clinician changes their own secret and nobody learns who exists by trying; "
            + "retiring a credential and putting one back are the operator's")
    @Proving({DboPromises.AUTH_DEACTIVATION_RETIRES_CREDENTIALS,
            DboPromises.AUTH_NO_SUBJECT_ENUMERATION, DboPromises.AUTH_RECOVERY_IS_AN_OPERATOR_ACT,
            DboPromises.AUTH_SELF_SERVICE_CHANGE})
    void aClinicianChangesTheirOwnSecret() {
        String human = accessFor("hermione", "granger9");
        Proves.that(DboPromises.AUTH_NO_SUBJECT_ENUMERATION,
                changeSecret(human, "hermione", "not-the-one", "uus9paroolimees") == 403
                        && changeSecret(human, "nobody-here", "granger9", "uus9paroolimees") == 403
                        && changeSecret(dbo.token(second), "hermione", "granger9", "uus9") == 403,
                "a wrong secret, a login nobody holds and a machine were not answered alike");

        Proves.that(DboPromises.AUTH_SELF_SERVICE_CHANGE,
                changeSecret(human, "hermione", "granger9", "uus9paroolimees") == 204
                        && changeSecret(human, "poppy", "pomfrey8", "pomfrey9") == 204
                        && signsIn("hermione", "uus9paroolimees")
                        && !signsIn("hermione", "granger9"),
                "the clinician could not change their own secrets, or the old one still works");

        Proves.that(DboPromises.AUTH_DEACTIVATION_RETIRES_CREDENTIALS,
                retire("poppy") == 204 && !signsIn("poppy", "pomfrey9")
                        && retire(names.value("never-was")) == 204,
                "a retired credential signs in, or retiring one that never was said otherwise");

        Proves.that(DboPromises.AUTH_RECOVERY_IS_AN_OPERATOR_ACT,
                admin("/credentials", "{\"login\":\"hermione\",\"secret\":\"granger9\","
                        + "\"personId\":\"" + healer + "\"}", dbo.token(second)) == 200
                        && signsIn("hermione", "granger9"),
                "provisioning the credential again did not put the clinician back");
    }

    @Test
    @Order(20)
    @DisplayName("a new clinician sets their own first secret from a grant that works once, "
            + "and the grant tells its asker nothing about who exists")
    @Proving({DboPromises.AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT,
            DboPromises.AUTH_NO_SUBJECT_ENUMERATION,
            DboPromises.AUTH_DEACTIVATION_RETIRES_CREDENTIALS})
    void aFirstSecretIsSetFromAOneTimeGrant() {
        assertEquals(200, admin("/credentials", "{\"login\":\"minerva\",\"secret\":\""
                + java.util.UUID.randomUUID() + "\",\"personId\":\"" + healer + "\"}",
                dbo.token(second)));
        String grant = mintGrant("minerva");
        Proves.that(DboPromises.AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT,
                redeem(grant, "kass9tabby") == 204 && signsIn("minerva", "kass9tabby")
                        && redeem(grant, "teine9paroolimees") == 403,
                "the grant did not set the holder's own secret, or it worked twice");

        String forSomebody = mintGrant("minerva");
        String forNobody = mintGrant("kedagi-pole-siin");
        Proves.that(DboPromises.AUTH_NO_SUBJECT_ENUMERATION,
                forSomebody.length() == forNobody.length()
                        && redeem(forNobody, "paroolimees9") == 403
                        && redeem("a-grant-nobody-minted", "paroolimees9") == 403,
                "minting looked the subject up, or a refusal said why");
        String spent = mintGrant("minerva");
        Proves.that(DboPromises.AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT,
                redeem(spent, "") == 403 && redeem(spent, "paroolimees9") == 403,
                "a grant survived a failed attempt and could be tried again");
        Proves.that(DboPromises.AUTH_DEACTIVATION_RETIRES_CREDENTIALS,
                retire("minerva") == 204 && redeem(mintGrant("minerva"), "paroolimees9") == 403,
                "a retired credential was set again through a grant");
    }

    @Test
    @Order(21)
    @DisplayName("a grant authorises its own redemption and nothing else: it is neither a "
            + "client secret nor a password")
    @Proving(DboPromises.AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT)
    void aGrantIsNotACredential() {
        String grant = mintGrant("hermione");
        HttpResponse<String> asASecret = formPost(oidc(second) + "/token",
                "grant_type=client_credentials&client_id=" + portal() + "&client_secret="
                        + encoded(grant));
        HttpResponse<String> asAPassword = frontChannelLogin("hermione", grant, null);
        Proves.that(DboPromises.AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT,
                !asASecret.body().contains("access_token")
                        && asAPassword.headers().firstValue("Location").orElse("")
                        .contains("error="),
                "a grant was taken as a credential: " + asASecret.body());
    }

    // ── and a clinic takes its FHIR version from a root, as records ──

    @Test
    @Order(22)
    @DisplayName("a clinic on a face holds its version's definitions as records the instant "
            + "it is served, and validates its writes against them")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void aClinicHoldsItsVersionAsRecords() {
        HttpResponse<String> patient = dbo.get(fhir(BANK) + "/StructureDefinition?url="
                + encoded("http://hl7.org/fhir/StructureDefinition/Patient"), dbo.token(BANK));
        Proves.that(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS,
                patient.statusCode() == 200 && patient.body().contains("\"type\":\"Patient\""),
                "the clinic is served without its version's definitions: " + patient.body());

        ATenantsDoor door = new ATenantsDoor(dbo, BANK);
        assertEquals(201, door.post("/Patient", aBankCustomer("female")).statusCode(),
                "a valid record was not accepted");
        HttpResponse<String> refused = door.post("/Patient", """
                {"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"%s"}],
                 "active":"maybe"}""".formatted(names.value("nid-refused")));
        Proves.that(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS,
                refused.statusCode() == 422,
                "the clinic validates against nothing: " + refused.body());
    }

    @Test
    @Order(23)
    @DisplayName("a code outside a required binding is refused by name, answered from the code "
            + "system the clinic took from its root, and the terminology arrived the same way")
    @Proving(DboPromises.TERM_BINDINGS_ANSWERED_FROM_RECORDS)
    void aBindingIsAnsweredFromTheRecordsTheClinicHolds() throws Exception {
        ATenantsDoor door = new ATenantsDoor(dbo, BANK);
        HttpResponse<String> unicorn = door.post("/Patient", aBankCustomer("unicorn"));
        Proves.that(DboPromises.TERM_BINDINGS_ANSWERED_FROM_RECORDS,
                unicorn.statusCode() == 422 && unicorn.body().contains("unicorn"),
                "a gender outside the required binding was accepted, or refused without "
                        + "naming it: " + unicorn.statusCode() + " " + unicorn.body());

        HttpResponse<String> lookup = dbo.get(fhir(BANK) + "/CodeSystem/$lookup?system="
                + encoded("http://terminology.hl7.org/CodeSystem/v3-MaritalStatus") + "&code=M",
                dbo.token(BANK));
        assertEquals(200, lookup.statusCode(), lookup.body());
        assertTrue(lookup.body().contains("Married"), lookup.body());
        // From the root, and not from a package read here: importing the
        // baseline from a carried package leaves a marker system behind.
        long imported = new WhatTheDatabaseHolds(environment, BANK).count(
                "SELECT count(*) FROM definitions.term_system "
                        + "WHERE url LIKE 'urn:dbo:terminology-baseline:%'");
        Proves.that(DboPromises.TERM_BINDINGS_ANSWERED_FROM_RECORDS, imported == 0,
                "the clinic imported the terminology baseline from a carried package");
    }

    @Test
    @Order(24)
    @DisplayName("another clinic declared on the same root is served already holding the "
            + "version, and searches by what the root defined")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void anotherClinicOnTheFaceSharesTheBase() {
        String onTheFace = names.tenant("on-the-face");
        dbo.declare(onTheFace, subscriber(onTheFace,
                "\"StructureDefinition\",\"SearchParameter\",\"ValueSet\",\"CodeSystem\"",
                "r4"));
        try {
            assertTrue(dbo.until(onTheFace, true, Duration.ofMinutes(10)),
                    "the clinic on the face did not come up: " + dbo.serving());
            HttpResponse<String> held = dbo.get(fhir(onTheFace) + "/StructureDefinition?url="
                    + encoded("http://hl7.org/fhir/StructureDefinition/Patient"),
                    dbo.token(onTheFace));
            Proves.that(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS,
                    held.body().contains("\"type\":\"Patient\"")
                            && statusOf(fhir(onTheFace) + "/Patient?gender=female",
                                    dbo.token(onTheFace)) == 200,
                    "a clinic on the root was served without the version, or cannot search by "
                            + "the parameters it defines: " + held.body());
        } finally {
            dbo.retract(onTheFace);
        }
    }

    @Test
    @Order(25)
    @DisplayName("a clinic whose chain does not carry the code systems is not served, and the "
            + "node says what it lacks; nor is one taking its version from another face's root")
    @Proving({DboPromises.TEN_READY_WHEN_ITS_CRITICAL_DEFINITIONS_ARRIVED,
            DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES})
    void aChainWithoutItsCodeSystemsIsRefusedByName() throws InterruptedException {
        String lacking = names.tenant("lacking");
        String converting = names.tenant("converting");
        dbo.declare(lacking, subscriber(lacking,
                "\"StructureDefinition\",\"SearchParameter\",\"ValueSet\"", "r4"));
        dbo.declare(converting, subscriber(converting,
                "\"StructureDefinition\",\"SearchParameter\"", "r5"));
        try {
            String lacks = whyNotServing(lacking, "CodeSystem");
            Proves.that(DboPromises.TEN_READY_WHEN_ITS_CRITICAL_DEFINITIONS_ARRIVED,
                    lacks.contains("CodeSystem") && !dbo.serving().contains(lacking),
                    "a chain without code systems was served, or refused for another reason: "
                            + lacks);
            String converts = whyNotServing(converting, "does not convert");
            assertTrue(converts.contains("does not convert") && !dbo.serving().contains(converting),
                    "an r5 clinic took its definitions from an r4 root, or failed for another "
                            + "reason than the one that matters: " + converts);
        } finally {
            dbo.retract(lacking);
            dbo.retract(converting);
        }
        assertThrows(IllegalArgumentException.class, () -> cloud.jengu.dbo.tenant.TenantSpec.parse("""
                {"code":"two-versions","face":"r4","audit":{"level":"none"},
                 "dependencies":[
                   {"name":"a","face":true,"types":["StructureDefinition"]},
                   {"name":"b","face":true,"types":["StructureDefinition"]}],
                 "types":[{"name":"StructureDefinition","identity":"canonical",
                           "handling":"replicated"}]}"""),
                "a clinic declaring two versions was not refused, and a tenant is one version");
    }

    // ── and the grants converge on what the clinic's configuration names ──

    @Test
    @Order(26)
    @DisplayName("what was granted reads back with the organisation it was granted at and the "
            + "scopes it was granted, and a withdrawn grant only when asked for")
    @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
    void whatWasGrantedReadsBack() {
        String held = grants("");
        Proves.that(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE,
                held.contains("\"organisation\":null")
                        && held.contains("\"organisation\":\"main-lab\"")
                        && held.contains("\"user/Observation.write\""),
                "the tenant-wide and the organisation's grant of one role do not read back as "
                        + "two grants, each with its scopes: " + held);
        Proves.that(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE,
                !rolesIn(held).contains("laborant"),
                "a withdrawn grant is in the default answer, so a client takes it for present: "
                        + held);
        String all = grants("?status=all");
        Proves.that(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE,
                all.contains("\"laborant\"") && all.contains("\"status\":\"withdrawn\"")
                        && all.contains("\"withdrawnAt\"")
                        && all.contains("\"user/Specimen.read\""),
                "the withdrawn grant is unreachable over the wire, or lost what it could do: "
                        + all);
    }

    @Test
    @Order(27)
    @DisplayName("a client converges the clinic on its configuration: read what is granted, "
            + "withdraw what configuration no longer names, read back agreement")
    @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
    void aClientConvergesTheGrantsOnItsConfiguration() {
        java.util.Set<String> named = java.util.Set.of("healer", "lab-tech");
        java.util.Set<String> held = rolesIn(grants(""));
        assertTrue(held.containsAll(named) && !named.containsAll(held),
                "nothing to converge away, so converging proves nothing: " + held);
        for (String role : held) {
            if (!named.contains(role)) {
                assertEquals(200, admin("/role-grants", "{\"role\":\"" + role
                        + "\",\"withdraw\":\"true\"}", dbo.token(second)));
            }
        }
        Proves.that(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE,
                rolesIn(grants("")).equals(named),
                "the clinic did not converge on what configuration names: " + grants(""));
    }

    @Test
    @Order(28)
    @DisplayName("the read stands behind the same scope as the writes, and a status it does "
            + "not know is refused by name")
    @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
    void theGrantsAreReadOnTheProvisioningPlane() {
        Proves.that(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE,
                dbo.get(oidc(second) + "/admin/role-grants", null).statusCode() == 401
                        && dbo.get(oidc(second) + "/admin/role-grants",
                                accessFor("hermione", "granger9")).statusCode() == 403,
                "the provisioning read answered somebody off the provisioning plane");
        HttpResponse<String> refused = dbo.get(oidc(second)
                + "/admin/role-grants?status=withdrawn", dbo.token(second));
        Proves.that(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE,
                refused.statusCode() == 400 && refused.body().contains("withdrawn"),
                "a status nobody defined was answered, so a caller believing it asked for the "
                        + "withdrawn ones reconciles against a shorter list: " + refused.body());
    }

    // ── and a directory is only ever opened behind the membrane ──

    @Test
    @Order(29)
    @DisplayName("a clinic declaring a staff directory over identity held in the clear is not "
            + "opened, and the deployment leaves a card saying the directory needs the membrane")
    @Proving(DboPromises.SCIM_DECLARED_PER_TENANT)
    void aDirectoryOverIdentityInTheClearIsRefused() throws InterruptedException {
        String clear = names.tenant("in-the-clear");
        dbo.declare(clear, """
                {"code":"%s","face":"r4","pdi":false,"audit":{"level":"writes"},
                 "scim":{"system":"%s"},"types":%s}""".formatted(clear, idp, staffTypes()));
        try {
            String said = "";
            long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (!said.contains("scim requires pdi") && System.nanoTime() < giveUp) {
                Thread.sleep(1000);
                said = cardFor(clear);
            }
            Proves.that(DboPromises.SCIM_DECLARED_PER_TENANT,
                    said.contains("scim requires pdi") && !dbo.serving().contains(clear),
                    "a directory over identity in the clear was opened, or refused without "
                            + "saying it needs the membrane: " + said);
        } finally {
            dbo.retract(clear);
        }
    }

    // ── and a partner who runs clinics follows their work without reading it ──

    @Test
    @Order(30)
    @DisplayName("a partner's credential reads a managed clinic's journey by run, is refused by "
            + "a clinic it does not manage, and never receives a document or a purpose")
    @Proving(DboPromises.TEN_A_PARTNER_MANAGES_TENANTS)
    void aPartnerFollowsTheWorkAndNothingElse() {
        // The relation is declared when the managed clinic is created, so the
        // partner is serving first.
        String partner = names.tenant("partner");
        String managed = names.tenant("managed");
        String basic = """
                [{"name":"Basic","identity":"internal","handling":"operational"}]""";
        dbo.declare(partner, """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":%s}"""
                .formatted(partner, basic));
        try {
            assertTrue(dbo.until(partner, true, Duration.ofMinutes(10)), "no partner");
            dbo.declare(managed, """
                    {"code":"%s","face":"r4","managedBy":"%s","audit":{"level":"writes"},
                     "types":%s}""".formatted(managed, partner, basic));
            assertTrue(dbo.until(managed, true, Duration.ofMinutes(10)), "no managed clinic");

            String step = names.prefix() + "-" + names.run() + ".lab.assay";
            var assay = cloud.jengu.dbo.core.process.StepDeclaration.of(step, "1.0",
                            cloud.jengu.dbo.work.WorkModel.DOMAIN)
                    .taking("specimen", "https://meristem.example/shape/specimen");
            authority(partner).ensureClient(names.value("support"), "support-secret",
                    List.of("system/*.read"));
            String partnerToken = token(partner, names.value("support"), "support-secret");
            authority(managed).ensureClient(names.value("bench"), "bench-secret",
                    List.of("work/" + step));

            var specimen = dbo.write(managed, "Basic",
                    "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"specimen-3f9a\"}}");
            assertTrue(specimen.accepted(), specimen.body());
            var runs = new cloud.jengu.dbo.work.Runs(tenants.store(managed).orElseThrow());
            var run = runs.of(assay, cloud.jengu.dbo.work.RunKind.PIPELINE, "followed",
                    java.util.Map.of("specimen", "Basic/" + specimen.idOrFail()));
            var bench = cloud.jengu.dbo.runner.http.HttpLane.to(
                    URI.create(dbo.at(managed) + "/work"),
                    () -> token(managed, names.value("bench"), "bench-secret"), managed,
                    names.value("bench"), new cloud.jengu.dbo.work.Executor(names.value("bench"),
                            "1.0", "example.meristem", cloud.jengu.dbo.work.Scope.BASELINE));
            bench.introduce(assay);
            var held = bench.claim(run, Duration.ofMinutes(5)).orElseThrow();
            bench.inputs(held);
            // A read with a stated purpose, by the practice itself: on its
            // trail with the purpose, which is the practice's to reveal.
            assertEquals(200, dbo.send(HttpRequest.newBuilder(URI.create(fhir(managed)
                            + "/Basic/" + specimen.idOrFail())).header("Purpose-Of-Use", "TREAT")
                    .GET(), dbo.token(managed)).statusCode());

            HttpResponse<String> journey = dbo.get(fhir(managed) + "/AuditEvent?run="
                    + encoded(held.key()), partnerToken);
            Proves.that(DboPromises.TEN_A_PARTNER_MANAGES_TENANTS,
                    journey.statusCode() == 200 && journey.body().contains("travel")
                            && journey.body().contains("\"value\":\"" + names.value("bench")
                            + "\""),
                    "the partner does not read the run's journey, hop by hop: " + journey.body());
            Proves.that(DboPromises.TEN_A_PARTNER_MANAGES_TENANTS,
                    !journey.body().contains("specimen-3f9a") && !journey.body().contains("TREAT"),
                    "a document or a purpose reached the partner: " + journey.body());
            HttpResponse<String> document = dbo.get(fhir(managed) + "/Basic/"
                    + specimen.idOrFail(), partnerToken);
            Proves.that(DboPromises.TEN_A_PARTNER_MANAGES_TENANTS,
                    (document.statusCode() == 404 || document.statusCode() == 403)
                            && !document.body().contains("specimen-3f9a"),
                    "the partner read a document: " + document.statusCode());
            Proves.that(DboPromises.TEN_A_PARTNER_MANAGES_TENANTS,
                    dbo.get(fhir(second) + "/AuditEvent?run=" + encoded(held.key()),
                            partnerToken).statusCode() == 401,
                    "a clinic that declared no partner knew the partner's credential");
            HttpResponse<String> own = dbo.get(fhir(managed) + "/AuditEvent?entity="
                    + specimen.idOrFail(), dbo.token(managed));
            Proves.that(DboPromises.TEN_A_PARTNER_MANAGES_TENANTS,
                    own.statusCode() == 200 && own.body().contains("TREAT"),
                    "the clinic does not read its own trail whole: " + own.body());
        } finally {
            dbo.retract(managed);
            dbo.retract(partner);
        }
    }

    // ── and a clinic's life: its database, its retraction, its name ──

    @Test
    @Order(31)
    @DisplayName("a token one clinic's authority issues validates there and nowhere else")
    @Proving(DboPromises.AUTH_TENANT_SCOPED_ISSUER)
    void aTokenIsValidatedOnlyByItsIssuer() {
        authority(clinic).ensureClient(names.value("published"), "published-secret",
                List.of("system/*.read"));
        var issued = authority(clinic).token(names.value("published"), "published-secret",
                "system/*.read");
        Proves.that(DboPromises.AUTH_TENANT_SCOPED_ISSUER,
                issued instanceof TenantAuthority.TokenResult.Issued minted
                        && authority(clinic).validate(minted.accessToken()).isPresent()
                        && authority(second).validate(minted.accessToken()).isEmpty(),
                "a token was validated by an authority that did not issue it");
    }

    @Test
    @Order(32)
    @DisplayName("a clinic's database is provisioned with the timeouts that keep one stuck "
            + "transaction from holding it, and its own vocabulary arrived as a recorded pass")
    @Proving(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP)
    void aClinicsDatabaseIsProvisionedAndItsVocabularyRecorded() throws Exception {
        String settings = WhatTheDatabaseHolds.theServer(environment).one(
                "SELECT array_to_string(s.setconfig, ',') FROM "
                        + "pg_db_role_setting s JOIN pg_database d ON d.oid = s.setdatabase "
                        + "WHERE d.datname = ? AND s.setrole = 0",
                "tenant_" + second.replace('-', '_'));
        assertTrue(settings != null && settings.contains("idle_in_transaction_session_timeout=60s")
                && settings.contains("transaction_timeout=300s"),
                "the clinic's database carries no timeouts: " + settings);

        var pass = new cloud.jengu.dbo.work.Runs(tenants.store(second).orElseThrow()).byKey(
                cloud.jengu.dbo.sync.ConfigApplication.PROCESS + "/"
                        + cloud.jengu.dbo.sync.ConfigApplication.STEP + "/" + second);
        Proves.that(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP,
                pass.isPresent() && pass.get().kind() == cloud.jengu.dbo.work.RunKind.SWEEP
                        && !pass.get().needsAPerson()
                        && ((Number) pass.get().tally().getOrDefault("read", 0L)).longValue() > 0
                        && pass.get().tally().get("read").equals(pass.get().tally().get("applied")),
                "the face's own vocabulary did not arrive as a recorded, closed pass: " + pass);
    }

    @Test
    @Order(33)
    @DisplayName("retracting a clinic stops serving it and keeps its data, so declaring it "
            + "again brings back what it held")
    void retractingIsNotErasing() throws InterruptedException {
        String paused = names.tenant("paused");
        String spec = """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(paused);
        dbo.declare(paused, spec);
        try {
            assertTrue(dbo.until(paused, true, Duration.ofMinutes(10)), "not served");
            var kept = dbo.write(paused, "Patient", """
                    {"resourceType":"Patient","name":[{"family":"Aiakas"}]}""");
            assertTrue(kept.accepted(), kept.body());
            dbo.retract(paused);
            assertTrue(dbo.until(paused, false, Duration.ofMinutes(3)), "still served");
            assertEquals(404, dbo.get(fhir(paused) + "/metadata", null).statusCode(),
                    "a retracted clinic still answers");
            dbo.declare(paused, spec);
            assertTrue(dbo.until(paused, true, Duration.ofMinutes(10)), "not served again");
            assertEquals(200, dbo.get(fhir(paused) + "/Patient/" + kept.idOrFail(),
                    dbo.token(paused)).statusCode(), "retracting the clinic erased its data");
        } finally {
            dbo.retract(paused);
        }
    }

    @Test
    @Order(34)
    @DisplayName("a clinic on a face nothing serves gets no database, and a long hyphenated "
            + "code is a clinic like any other")
    void whatCannotBeServedIsNotProvisioned() throws Exception {
        String unserved = names.tenant("on-no-face");
        String longCode = names.tenant("e2e-us-xapi-distributor-onboards-customer-20260815");
        dbo.declare(unserved, """
                {"code":"%s","face":"kuues","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(unserved));
        dbo.declare(longCode, """
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(longCode));
        try {
            assertTrue(dbo.until(longCode, true, Duration.ofMinutes(10)),
                    "a long hyphenated code did not come up: " + longCode);
            assertEquals(200, dbo.get(fhir(longCode) + "/metadata", null).statusCode());
            long databases = WhatTheDatabaseHolds.theServer(environment).count(
                    "SELECT count(*) FROM pg_database WHERE datname = ?",
                    "tenant_" + unserved.replace('-', '_'));
            assertFalse(dbo.serving().contains(unserved), "a clinic on no face was served");
            assertEquals(0, databases, "a clinic nothing can serve was given a database");
        } finally {
            dbo.retract(unserved);
            dbo.retract(longCode);
        }
    }

    @Test
    @Order(35)
    @DisplayName("a zone that names no identity broker is its own: it runs the ceremony its "
            + "members federate to, and its members serve")
    @Proving(DboPromises.AUTH_A_ZONE_IS_ITS_OWN_BROKER)
    void aZoneIsItsOwnBroker() {
        // Rowling Land names no broker, so declaring it for its rules and its
        // terminology did not oblige anybody to stand up an identity provider.
        // What makes it a broker anyway is the hub holding keys of its own to
        // sign what it asserts.
        String base = dbo.at("rl");
        String hub = base.substring(0, base.indexOf("/t/")) + "/z/rl/hub/jwks.json";
        HttpResponse<String> keys = dbo.get(hub, null);
        Proves.that(DboPromises.AUTH_A_ZONE_IS_ITS_OWN_BROKER,
                keys.statusCode() == 200 && keys.body().contains("\"keys\""),
                "the zone's hub has no keys of its own, so nothing federates to it: "
                        + keys.statusCode() + " " + keys.body());
        // And its members serve, which is the half that would be missing if a
        // zone without a broker held its tenants out of service.
        Proves.that(DboPromises.AUTH_A_ZONE_IS_ITS_OWN_BROKER,
                dbo.serving().containsAll(List.of("hogwarts", "st-jerome", "gringotts")),
                "a member of a zone that is its own broker is not served: " + dbo.serving());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** What the deployment's application pass says about one declaration, if anything. */
    private String cardFor(String code) {
        var runs = new cloud.jengu.dbo.work.Runs(tenants.store("mom").orElseThrow());
        return runs.byKey(cloud.jengu.dbo.sync.ConfigApplication.PROCESS + "/"
                        + cloud.jengu.dbo.sync.ConfigApplication.STEP + "/deployment")
                .map(pass -> runs.items(pass).stream()
                        .map(cloud.jengu.dbo.work.Run::item)
                        .filter(item -> item != null && item.reference().startsWith(code))
                        .map(item -> String.valueOf(item.message()))
                        .reduce("", String::concat))
                .orElse("");
    }

    /** Rowling Land's bank, which takes its version from the r4 root. */
    private static final String BANK = "gringotts";
    private static final String R4_ROOT = "fhir-r4";

    private String aBankCustomer(String gender) {
        return """
                {"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"%s"}],
                 "gender":"%s"}""".formatted(names.value("nid-" + gender), gender);
    }

    /** A clinic of this story's, taking the given definition types from the r4 root. */
    private String subscriber(String code, String fromTheRoot, String face) {
        StringBuilder types = new StringBuilder();
        for (String type : fromTheRoot.replace("\"", "").split(",")) {
            types.append("{\"name\":\"").append(type)
                    .append("\",\"identity\":\"canonical\",\"handling\":\"replicated\"},");
        }
        return """
                {"code":"%s","face":"%s","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","face":true,"types":[%s]}],
                 "types":[%s{"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code, face, R4_ROOT, fromTheRoot, types);
    }

    /**
     * What the node says about a tenant it is not serving, once it says what
     * was expected — a tenant's bring-up is retried, and an earlier pass may
     * have failed for a reason of the moment before reaching the one asked
     * about. The last thing said is returned if it never does.
     */
    private String whyNotServing(String code, String expected) throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        String why = "";
        while (!why.contains(expected) && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
            HttpResponse<String> rows = dbo.get(URI.create(dbo.at(BANK)).resolve("/runtime/tenants")
                    .toString(), "stories-ops");
            for (Object row : (List<?>) ((java.util.Map<?, ?>) cloud.jengu.dbo.core.wire.RecordWire
                    .read(rows.body())).get("tenants")) {
                java.util.Map<?, ?> fields = (java.util.Map<?, ?>) row;
                if (code.equals(fields.get("code")) && fields.get("why") != null) {
                    why = String.valueOf(fields.get("why"));
                }
            }
        }
        return why;
    }

    private static final String REDIRECT = "http://127.0.0.1/cb";
    private static final String PORTAL_SECRET = "portal-secret";

    private String healer;
    private String healerPractitioner;
    private HttpResponse<String> healerRole;
    private String healerToken;
    private String healerRefresh;

    private String webApp() {
        return names.value("web-app");
    }

    private String portal() {
        return names.value("portal");
    }

    private String oidc(String tenant) {
        return dbo.at(tenant) + "/oidc";
    }

    private static String verifier() {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String challenge(String verifier) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String claimsOf(String jwt) {
        return new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
    }

    private static String codeIn(HttpResponse<String> login) {
        String location = login.headers().firstValue("Location").orElseThrow(
                () -> new AssertionError("no redirect: " + login.body()));
        return java.util.Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(pair -> pair.startsWith("code="))
                .map(pair -> java.net.URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8))
                .findFirst().orElseThrow(() -> new AssertionError("no code in " + location));
    }

    private HttpResponse<String> formPost(String url, String form) {
        return dbo.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), null);
    }

    private HttpResponse<String> frontChannelLogin(String login, String password, String nonce) {
        return formPost(oidc(second) + "/authorize/login", "client_id=" + portal()
                + "&redirect_uri=" + encoded(REDIRECT)
                + (nonce == null ? "" : "&nonce=" + encoded(nonce))
                + "&login=" + encoded(login) + "&password=" + encoded(password));
    }

    /**
     * Whether a front-channel sign-in succeeded. A refused one is an error
     * redirect, not a status, so the location is what differs.
     */
    private boolean signsIn(String login, String password) {
        HttpResponse<String> attempt = frontChannelLogin(login, password, null);
        String location = attempt.headers().firstValue("Location").orElse("");
        return attempt.statusCode() == 302 && location.contains("code=")
                && !location.contains("error=");
    }

    private HttpResponse<String> signIn(String login, String password, String nonce) {
        return formPost(oidc(second) + "/token", "grant_type=authorization_code&client_id="
                + portal() + "&code=" + codeIn(frontChannelLogin(login, password, nonce))
                + "&redirect_uri=" + encoded(REDIRECT) + "&client_secret="
                + encoded(PORTAL_SECRET));
    }

    private String accessFor(String login, String password) {
        HttpResponse<String> tokens = signIn(login, password, null);
        return dbo.says(tokens).one("access_token").orElseThrow(
                () -> new AssertionError("no token for " + login + ": " + tokens.body()));
    }

    private HttpResponse<String> adminResponse(String path, String json, String bearer) {
        return dbo.send(HttpRequest.newBuilder(URI.create(oidc(second) + "/admin" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), bearer);
    }

    private int admin(String path, String json, String bearer) {
        return adminResponse(path, json, bearer).statusCode();
    }

    private int changeSecret(String bearer, String login, String current, String replacement) {
        return dbo.send(HttpRequest.newBuilder(URI.create(oidc(second) + "/credentials"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("login=" + encoded(login)
                        + "&current_secret=" + encoded(current)
                        + "&new_secret=" + encoded(replacement))), bearer).statusCode();
    }

    private int retire(String login) {
        return admin("/credentials", "{\"login\":\"" + login + "\",\"status\":\"retired\"}",
                dbo.token(second));
    }

    private String mintGrant(String login) {
        HttpResponse<String> minted = adminResponse("/secret-grants",
                "{\"login\":\"" + login + "\",\"minutes\":\"30\"}", dbo.token(second));
        assertEquals(200, minted.statusCode(), minted.body());
        return dbo.says(minted).one("grant").orElseThrow(
                () -> new AssertionError("no grant in " + minted.body()));
    }

    private String grants(String query) {
        HttpResponse<String> answered = dbo.get(oidc(second) + "/admin/role-grants" + query,
                dbo.token(second));
        assertEquals(200, answered.statusCode(), answered.body());
        return answered.body();
    }

    private static java.util.Set<String> rolesIn(String body) {
        java.util.Set<String> roles = new java.util.TreeSet<>();
        java.util.regex.Matcher found = java.util.regex.Pattern
                .compile("\"role\":\"([^\"]+)\"").matcher(body);
        while (found.find()) {
            roles.add(found.group(1));
        }
        return roles;
    }

    private int redeem(String grant, String chosen) {
        return formPost(oidc(second) + "/secret-grants/redeem", "grant=" + encoded(grant)
                + "&new_secret=" + encoded(chosen)).statusCode();
    }

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

    /** The clinic's application, as a client of the clinic. */
    private static String app() {
        return cloud.jengu.dbo.samples.server.OpeningAClinic.APPLICATION;
    }

    /** Its secret, as the application is configured with it. */
    private String appSecret() {
        return environment.getRequiredProperty("clinic.application.secret");
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
    private String untilSignedIn(String tenant, String clientId, String secret) {
        long giveUp = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (System.nanoTime() < giveUp) {
            HttpResponse<String> issued = formPost(oidc(tenant) + "/token",
                    "grant_type=client_credentials&client_id="
                            + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                            + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8));
            if (issued.statusCode() == 200) {
                return dbo.says(issued).one("access_token").orElseThrow();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return token(tenant, clientId, secret);
    }

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
