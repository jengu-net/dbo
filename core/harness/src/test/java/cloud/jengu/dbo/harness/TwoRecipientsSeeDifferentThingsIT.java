package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Audience;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.policy.PolicyObjectStore;
import cloud.jengu.dbo.policy.TenantPolicies;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One clinic, one set of records, and three recipients who each get something
 * different.
 *
 * <p>Before this, a tenant had two dials and neither knew who was asking. A
 * type's declared travel says whether it may go into a backup or an export —
 * kinds of destination, not people. A face's coarsening reduces a value the
 * same way for everybody, by construction: it takes a value and returns a
 * value. So a clinic sharing with a referral hospital and a research recipient
 * shared the same thing with both, or declared a type unshareable and shared
 * it with neither. The moment there is a second partner who should see
 * something different, the type-level dial has run out.
 *
 * <p>What was missing was not a mechanism to reduce with — that existed — but
 * that the reduction is chosen <b>per recipient</b>. So the recipient travels
 * beside the request, as a per-request fact rather than a scope, and what they
 * receive follows a declaration the tenant wrote rather than what they asked
 * for.
 *
 * <p>The declaration is configuration, deliberately. A store that accumulated
 * one rule per partner would be a policy engine nobody can audit; configuration
 * is swept, reviewed and diffed like everything else a tenant declares.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoRecipientsSeeDifferentThingsIT {

    private static final String EID = "https://ee.ee/eid";

    static PolicyObjectStore clinic;
    static String patientId;
    static String observationId;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("TwoRecipientsSeeDifferentThingsIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        // The referral hospital needs the patient and the observation, whole:
        // a referral without a date of birth is clinically useless. The
        // research recipient needs the observations and must never receive the
        // patient. One record set, two answers.
        // The audit types too: disclosing a person with a stated purpose is
        // recorded against that purpose whatever the audit level says, so a
        // store without them refuses the very read this test is about.
        java.util.List<cloud.jengu.dbo.core.api.TypeRegistration> types =
                new java.util.ArrayList<>(new R4Personality(List.of(
                        FhirTypeConfig.identifier("Patient", EID),
                        FhirTypeConfig.internal("Observation"))).registrations());
        types.addAll(cloud.jengu.dbo.policy.AuditModel.registrations());
        clinic = new PolicyObjectStore(
                new PgObjectStore(ds, types),
                TenantPolicies.parse(Map.of("disclosure", Map.of("perAudience", Map.of(
                        "referral", Map.of("types", List.of("Patient", "Observation"),
                                "reveals", "include"),
                        "research", Map.of("types", List.of("Observation"),
                                "reveals", "omit"))))));

        patientId = clinic.put(PutRequest.create("Patient", ("""
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"38001010001"}],
                 "name":[{"family":"Haigla"}],"birthDate":"1980-01-01"}""".formatted(EID))
                .getBytes(StandardCharsets.UTF_8))).id();
        observationId = clinic.put(PutRequest.create("Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"haemoglobin"},"valueQuantity":{"value":135,"unit":"g/L"}}"""
                .getBytes(StandardCharsets.UTF_8))).id();
    }

    @AfterEach
    void noAudienceLeaksIntoTheNextTest() {
        Audience.clear();
        cloud.jengu.dbo.core.api.Disclosure.clear();
    }

    @Test
    @DisplayName("the same record answers two recipients differently, without either being "
            + "told what the other got")
    @Proving(DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED)
    void oneRecordTwoAnswers() {
        Audience.serving("referral");
        assertTrue(clinic.get("Patient", patientId).isPresent(),
                "the hospital this clinic refers to cannot see the patient it is being "
                        + "referred, which is the case the whole mechanism exists for");
        assertTrue(clinic.get("Observation", observationId).isPresent());

        Audience.serving("research");
        assertTrue(clinic.get("Observation", observationId).isPresent(),
                "the research recipient must still get what it is declared for");
        assertTrue(clinic.get("Patient", patientId).isEmpty(),
                "the research recipient was handed the patient, so a tenant sharing with "
                        + "two partners still shares the same thing with both");
    }

    @Test
    @DisplayName("a type outside the declaration is absent rather than refused, so nobody "
            + "learns it is here")
    @Proving(DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED)
    void whatIsNotTheirsIsSimplyNotThere() {
        Audience.serving("research");

        assertEquals(List.of(), clinic.select(Criteria.of("Patient")),
                "a search reached a type this audience is not answered about");
        assertEquals(0, clinic.count(Criteria.of("Patient")),
                "a count told them how many of a type they may not see exist, which is the "
                        + "same leak as the search with one number instead of a body");
        assertEquals(List.of(), clinic.history("Patient", patientId),
                "history answered about a type outside the declaration");
        assertEquals(List.of(), clinic.getByIdentifier("Patient",
                        List.of(new cloud.jengu.dbo.core.api.Identifier(EID, "38001010001"))),
                "an identifier lookup crossed the declaration, which is worse than the id "
                        + "lookup: an identifier is a thing an outsider can guess");
    }

    @Test
    @DisplayName("an audience nobody declared sees nothing, rather than seeing what the "
            + "tenant sees")
    @Proving(DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED)
    void anUndeclaredAudienceIsNotTheTenant() {
        Audience.serving("a-partner-who-was-removed");

        assertTrue(clinic.get("Observation", observationId).isEmpty(),
                "a name nobody declared was answered as though it were the tenant itself — "
                        + "and the two ways to arrive here are a typo in a serving surface "
                        + "and a partner who was removed, which both want silence");
        assertTrue(clinic.get("Patient", patientId).isEmpty());
    }

    @Test
    @DisplayName("naming no audience is the tenant's own request, and nothing about it "
            + "changes")
    @Proving(DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED)
    void theTenantsOwnRequestIsUntouched() {
        // The control. Almost every request in this store names no audience,
        // and a mechanism that quietly narrowed those would be a far worse
        // defect than the one it was built to fix.
        assertTrue(clinic.get("Patient", patientId).isPresent(),
                "a request naming no audience stopped seeing the tenant's own records");
        assertEquals(1, clinic.select(Criteria.of("Patient")).size());
        assertFalse(clinic.history("Patient", patientId).isEmpty());

        String stored = new String(clinic.get("Patient", patientId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        assertTrue(stored.contains("1980-01-01"),
                "the tenant's own read lost the birth date, so the audience machinery is "
                        + "reducing requests that named nobody: " + stored);
    }

    @Test
    @DisplayName("what a recipient receives follows the declaration, not what it asked for")
    @Proving(DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED)
    void askingForMoreDoesNotGetMore() {
        // A recipient that could negotiate upwards would make the declaration
        // advice. Research is declared at OMIT; asking for the whole record
        // with a purpose is exactly what a well-meaning client does.
        cloud.jengu.dbo.core.api.Disclosure.set(
                cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE, "HRESCH");
        Audience.serving("research");
        clinic.get("Observation", observationId);

        assertEquals(cloud.jengu.dbo.core.api.Disclosure.Mode.OMIT,
                cloud.jengu.dbo.core.api.Disclosure.mode(),
                "the recipient asked for the whole record and the declaration said "
                        + "otherwise, and the request won");

        // And the tenant's own read still gets what it asks for: the override
        // is about being answered FOR somebody, not about asking at all.
        Audience.clear();
        cloud.jengu.dbo.core.api.Disclosure.forAudience(null);
        cloud.jengu.dbo.core.api.Disclosure.set(
                cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE, "TREAT");
        assertEquals(cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE,
                cloud.jengu.dbo.core.api.Disclosure.mode(),
                "the tenant's own request stopped being able to ask for what it needs");
    }
}
