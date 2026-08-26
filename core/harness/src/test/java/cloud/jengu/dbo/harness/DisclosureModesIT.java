package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import java.util.List;
import cloud.jengu.dbo.core.api.DisclosureRefusedException;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a read of a person discloses is a decision, not a consequence of holding
 * a key (#114).
 *
 * <p>Before this, PDI was protection at rest plus erasure rights: any caller
 * who could read a person type got the name, the address and the identifiers in
 * the clear, because the store decrypted whenever a key existed. A tenant that
 * turned {@code pdi} on was told its data was protected and every existing
 * caller carried on seeing everything.
 *
 * <p>Three modes now, and <b>the default is the strict one</b>. A surface that
 * has not thought about disclosure cannot leak by saying nothing.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisclosureModesIT {

    private static final String PATIENT = """
            {"resourceType":"Patient","birthDate":"1970-01-01",
             "name":[{"family":"Salakas"}],
             "telecom":[{"system":"email","value":"salakas@hogwarts.scot"}]}""";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static ObjectStore store;
    static String personId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-disclosure");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("DisclosureModesIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve("avaldus.json"), """
                {"code":"avaldus","fhirVersion":"r4","pdi":true,
                 "audit":{"level":"full"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Practitioner","identity":"internal","handling":"operational"}]}""");
        // The same tenant shape with the ordinary audit level, which is what
        // made an identifying read untraceable: a disclosure has to be recorded
        // whatever a tenant chose to keep of ordinary traffic (#114).
        Files.writeString(dir.resolve("vaikne.json"), """
                {"code":"vaikne","fhirVersion":"r4","pdi":true,
                 "audit":{"level":"writes"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("avaldus") && up.contains("vaikne"));
        store = manager.runtime("avaldus").orElseThrow().engine();
        personId = store.put(PutRequest.create("Patient",
                PATIENT.getBytes(StandardCharsets.UTF_8))).id();
    }

    @org.junit.jupiter.api.BeforeEach
    void aCallerIsPresent() {
        // These assert what a CALLER's search does. The store's own
        // resolutions — an authority authenticating somebody — are not
        // guarded, so a test with no caller would be testing the wrong path.
        cloud.jengu.dbo.core.api.Caller.set("test-client");
    }

    @AfterEach
    void saidNothing() {
        Disclosure.clear();
        cloud.jengu.dbo.core.api.Caller.clear();
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    private static String read() {
        return new String(store.get("Patient", personId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
    }

    /**
     * The default, and the whole point of the change: a caller that says
     * nothing sees less than it did yesterday.
     */
    @Test
    void sayingNothingOmitsTheIdentity() {
        String read = read();
        assertFalse(read.contains("Salakas"),
                "a caller that stated no purpose must not receive a name: " + read);
        assertFalse(read.contains("__pdiEnc"),
                "nor the ciphertext block, which it cannot read and should not carry: " + read);
        assertTrue(read.contains("\"birthDate\":\"1970\""),
                "but the coarse value stays — omission is not erasure, and a clinician "
                        + "still needs an age: " + read);
        assertFalse(read.contains("1970-01-01"), read);
    }

    /** Asking for the whole person without saying why is refused, not quietly downgraded. */
    @Test
    void includingWithoutAPurposeIsRefused() {
        Disclosure.set(Disclosure.Mode.INCLUDE);
        DisclosureRefusedException refused =
                assertThrows(DisclosureRefusedException.class, DisclosureModesIT::read);
        assertTrue(refused.getMessage().contains("PurposeOfUse"),
                "the refusal says what is missing: " + refused.getMessage());
    }

    /** With a purpose, the whole person — which is what an authorised read is for. */
    @Test
    void includingWithAPurposeDisclosesTheWholePerson() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        String read = read();
        assertTrue(read.contains("Salakas") && read.contains("1970-01-01"),
                "a stated purpose and the right to read gets the person: " + read);
    }

    /**
     * A search that would match on an identity is refused, never silently
     * empty (#115).
     *
     * <p>Under the membrane the identifying elements are not in the inner
     * payload, so the query matches nothing and returns an empty result — which
     * reads exactly like <i>nobody here is called that</i>. That silence is the
     * identifying access nobody can audit, because no read happened.
     */
    @Test
    void searchingByAnIdentityWithoutAPurposeIsRefused() {
        cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException refused = assertThrows(
                cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException.class,
                () -> store.select(cloud.jengu.dbo.core.api.Criteria.of("Patient")
                        .eq("name", cloud.jengu.dbo.core.api.EnvelopeValue.of("salakas"))));
        assertTrue(refused.getMessage().contains("PurposeOfUse"),
                "the refusal says what is missing: " + refused.getMessage());
    }

    /**
     * And by every name the parameter goes by. A guard that knew only the
     * element names would refuse {@code name} and wave {@code email} through,
     * which is worse than no guard because it looks like one.
     */
    @Test
    void theGuardKnowsWhatEachParameterReaches() {
        // Envelope paths, hyphens already turned to underscores by the surface,
        // plus the exact-match twin a string parameter writes beside itself.
        for (String path : new String[] {"family", "given", "email", "phone", "address_city",
                "identifier", "name_xct"}) {
            assertThrows(cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException.class,
                    () -> store.select(cloud.jengu.dbo.core.api.Criteria.of("Patient")
                            .eq(path, cloud.jengu.dbo.core.api.EnvelopeValue.of("x"))),
                    path + " reaches an identifying element and must be refused like the rest");
        }
    }

    /**
     * A question about the record rather than about the person is not refused.
     *
     * <p>Asserted as "does not throw" rather than by counting matches: what
     * matters here is that the guard does not swallow ordinary search, and a
     * count would be asserting something about indexing instead.
     */
    @Test
    void searchingBySomethingThatIsNotAnIdentityIsNotRefused() {
        store.select(cloud.jengu.dbo.core.api.Criteria.of("Patient")
                .eq("gender", cloud.jengu.dbo.core.api.EnvelopeValue.of("male")));
    }

    /**
     * "Who is behind this address" is answerable again, from the index rather
     * than from plaintext (#115 point 2).
     *
     * <p>It is the lookup that breaks first when the membrane goes on — an
     * ordinary provisioning and sign-in question, not an investigative one —
     * and it is answered by hashing the term and matching hashes, so no
     * plaintext is at rest or in the query.
     */
    /**
     * Exact identifier resolution through the vault (#136): "which record
     * claims this identifier" is the question every external-identity lane
     * starts with, and the standard FHIR spelling of it is an exact
     * identifier token search. The match runs over the claim index's HMACs;
     * what comes back is served under the caller's disclosure mode like any
     * other read.
     */
    @Test
    void anExactIdentifierLookupResolvesTheClaimingRecord() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        String id = store.put(PutRequest.create("Patient", ("""
                {"resourceType":"Patient","name":[{"family":"Leidja"}],
                 "identifier":[{"system":"urn:test:scim","value":"ext-leidja"}]}""")
                .getBytes(StandardCharsets.UTF_8))).id();

        Criteria byIdentifier = Criteria.of("Patient")
                .eq("identifier", EnvelopeValue.token("urn:test:scim", "ext-leidja"));
        List<StoredObject> found = store.select(byIdentifier);
        assertEquals(1, found.size(), "the claiming record is found");
        assertEquals(id, found.get(0).id(), "and it is the record that claimed the value");
        assertTrue(new String(found.get(0).payload(), StandardCharsets.UTF_8).contains("Leidja"),
                "served disclosed, because a purpose was stated");

        assertEquals(1, store.count(byIdentifier), "count agrees");
        var paged = store.page(byIdentifier, null);
        assertEquals(1, paged.items().size(), "page — the path a REST search reaches");
        assertTrue(paged.drained(), "a claim answers at most once, so the chunk is terminal");
    }

    /** Stating no purpose refuses identifier resolution like any identifying access. */
    @Test
    void anIdentifierLookupWithoutAPurposeIsRefused() {
        assertThrows(cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException.class,
                () -> store.select(Criteria.of("Patient")
                        .eq("identifier", EnvelopeValue.token("urn:test:scim", "ext-leidja"))),
                "knowing which record claims a value is knowing something about them");
    }

    /**
     * A bare value asks every system at once — enumeration wearing a smaller
     * coat — so it stays refused rather than answered or silently empty.
     */
    @Test
    void aBareIdentifierValueDoesNotResolve() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        assertThrows(cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException.class,
                () -> store.select(Criteria.of("Patient")
                        .eq("identifier", EnvelopeValue.of("ext-leidja"))));
    }

    /**
     * Narrowing a result set the store cannot fully evaluate is how a wrong
     * answer gets a confident shape — same rule as every identifying lookup.
     */
    @Test
    void anIdentifierCombinedWithAnotherPredicateIsRefused() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        assertThrows(cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException.class,
                () -> store.select(Criteria.of("Patient")
                        .eq("identifier", EnvelopeValue.token("urn:test:scim", "ext-leidja"))
                        .eq("gender", EnvelopeValue.of("male"))));
    }

    /** A value nobody claims answers "nobody" — an empty list, not a refusal. */
    @Test
    void anUnclaimedIdentifierAnswersNobody() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        Criteria unknown = Criteria.of("Patient")
                .eq("identifier", EnvelopeValue.token("urn:test:scim", "ext-nobody"));
        assertEquals(0, store.select(unknown).size(), "nobody matches");
        assertEquals(0, store.count(unknown));
        assertTrue(store.page(unknown, null).drained());
    }

    /**
     * Resolution is type-scoped for free: claims are keyed by the claiming
     * record's own id, so a value a Patient claims answers nothing to a
     * Practitioner question — the capacity asked about is the capacity found.
     */
    @Test
    void aValueClaimedByAPatientAnswersNothingForAPractitioner() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        store.put(PutRequest.create("Patient", ("""
                {"resourceType":"Patient","name":[{"family":"Patsient"}],
                 "identifier":[{"system":"urn:test:scim","value":"ext-patsient"}]}""")
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, store.select(Criteria.of("Practitioner")
                        .eq("identifier", EnvelopeValue.token("urn:test:scim", "ext-patsient")))
                .size(), "the value is claimed, but not by a practitioner");
    }

    @Test
    void anExactTelecomLookupAnswersFromTheIndex() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        List<StoredObject> found = store.select(Criteria.of("Patient")
                .eq("email", EnvelopeValue.of("salakas@hogwarts.scot")));
        assertEquals(1, found.size(), "the person behind the address is found");
        assertTrue(new String(found.get(0).payload(), StandardCharsets.UTF_8)
                        .contains("Salakas"),
                "and comes back disclosed, because a purpose was stated");
    }

    /**
     * The same question, through every method a surface might call (#118).
     *
     * <p>The lookup was wired into {@code select} and {@code count} and not
     * into {@code page}, and a FHIR search over REST is a paged read — so the
     * feature worked everywhere except the one path a consumer actually
     * reaches, and answered {@code notMatchable} there. The test that covered
     * it called {@code select} directly, which is why it passed while the
     * consumer-facing path was broken.
     *
     * <p>Asserted together rather than separately, because the asymmetry is
     * invisible from any one of them.
     */
    @Test
    void everyMethodASurfaceMightCallGivesTheSameAnswer() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        Criteria byAddress = Criteria.of("Patient")
                .eq("email", EnvelopeValue.of("salakas@hogwarts.scot"));

        assertEquals(1, store.select(byAddress).size(), "select");
        assertEquals(1, store.count(byAddress), "count");

        var paged = store.page(byAddress, null);
        assertEquals(1, paged.items().size(), "page — the one a REST search reaches");
        assertNull(paged.nextCursor(),
                "an exact lookup is bounded, so there is nothing to page through");
        assertTrue(paged.drained(), "and the chunk says so");
    }

    /**
     * Two people share a phone and neither of them is wrong.
     *
     * <p>This is why telecom is indexed and never CLAIMED. A store that
     * refused the second household member would be deciding something it has
     * no basis to decide, and a lookup that answered with one of them
     * arbitrarily would be a wrong answer wearing the shape of a right one.
     */
    @Test
    void aSharedAddressFindsEverybodyHoldingIt() {
        store.put(PutRequest.create("Patient", ("""
                {"resourceType":"Patient","name":[{"family":"Teine"}],
                 "telecom":[{"system":"phone","value":"+3725550000"}]}""")
                .getBytes(StandardCharsets.UTF_8)));
        store.put(PutRequest.create("Patient", ("""
                {"resourceType":"Patient","name":[{"family":"Kolmas"}],
                 "telecom":[{"system":"phone","value":"+3725550000"}]}""")
                .getBytes(StandardCharsets.UTF_8)));

        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        assertEquals(2, store.select(Criteria.of("Patient")
                        .eq("phone", EnvelopeValue.of("+3725550000"))).size(),
                "both people holding the number are found, and neither is refused a write");
    }

    /**
     * An auditor asking "did anybody look this person up" can find out, and the
     * address is nowhere in the answer (#115 point 3).
     *
     * <p>The trail is append-only against everyone, so a plaintext address in
     * it would put an immutable audit and an erasure right in direct conflict.
     * A fingerprint puts them in no conflict at all: the auditor holds the
     * address already, computes the same value, and looks.
     */
    @Test
    void aLookupIsAnswerableLaterWithoutTheAddressBeingWrittenDown() throws Exception {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        store.select(Criteria.of("Patient")
                .eq("email", EnvelopeValue.of("salakas@hogwarts.scot")));
        Disclosure.clear();

        assertFalse(auditSays("avaldus", "salakas@hogwarts.scot"),
                "the address itself must never reach a trail nobody can amend");
        assertTrue(auditSays("avaldus", "\"matched\":\""),
                "and the fingerprint of what was matched must, or nobody can ask later");
    }

    /**
     * A tenant that keeps no record of ordinary reads still records a
     * disclosure (#114).
     *
     * <p>The level is a preference about volume; the disclosure record is a
     * requirement. Answering both with one dial left an identifying read at
     * {@code audit=writes} leaving nothing behind at all — the purpose stated,
     * and stated to nobody.
     */
    @Test
    void aDisclosureIsRecordedEvenWhereOrdinaryReadsAreNot() throws Exception {
        ObjectStore quiet = manager.runtime("vaikne").orElseThrow().engine();
        String id = quiet.put(PutRequest.create("Patient",
                PATIENT.getBytes(StandardCharsets.UTF_8))).id();

        // an ordinary read: this tenant keeps nothing of those
        quiet.get("Patient", id);
        assertFalse(auditSays("vaikne", "\"interaction\":\"read\""),
                "the tenant's own choice about ordinary traffic still stands");

        Disclosure.set(Disclosure.Mode.INCLUDE, "HRESCH");
        quiet.get("Patient", id);
        Disclosure.clear();
        assertTrue(auditSays("vaikne", "\"purpose\":\"HRESCH\""),
                "but an identity disclosed leaves why, whatever the level");
    }

    /** The same lookup without a purpose is still refused — it is still identifying. */
    @Test
    void anExactLookupIsStillAnIdentifyingAccess() {
        assertThrows(cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException.class,
                () -> store.select(Criteria.of("Patient")
                        .eq("email", EnvelopeValue.of("salakas@hogwarts.scot"))),
                "knowing who holds an address is knowing something about them");
    }

    /**
     * Stating a purpose does not conjure a capability. The store holds the name
     * and cannot match on it, and says so rather than answering empty.
     */
    @Test
    void aPurposeDoesNotMakeANameSearchable() {
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException refused = assertThrows(
                cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException.class,
                () -> store.select(cloud.jengu.dbo.core.api.Criteria.of("Patient")
                        .eq("name", cloud.jengu.dbo.core.api.EnvelopeValue.of("salakas"))));
        assertTrue(refused.getMessage().contains("cannot match"),
                "a different fact from the missing purpose, and said differently: "
                        + refused.getMessage());
    }

    /**
     * The purpose outlives the request, which is the only part of a disclosure
     * that can.
     *
     * <p>The read itself leaves nothing behind. "Who saw this person, and why"
     * is the question somebody asks a year later, and it can only be answered
     * if the answer was written down at the time.
     */
    @Test
    void thePurposeIsRecordedWhereItCanBeAskedAboutLater() throws Exception {
        Disclosure.set(Disclosure.Mode.INCLUDE, "ETREAT");
        read();
        Disclosure.clear();

        assertTrue(auditSays("avaldus", "\"purpose\":\"ETREAT\""),
                "an identifying read must leave why it happened in the trail");
        // Stated so the limit is visible rather than discovered: this tenant
        // audits reads. A tenant at audit=writes records no read at all, so an
        // identifying disclosure leaves nothing — the purpose is stated to
        // nobody. Whether an identifying read should be audited REGARDLESS of
        // level is a policy question #114 does not settle and this test does
        // not decide.
    }

    /** Whether any audit entry this tenant holds carries the phrase. */
    private static boolean auditSays(String tenant, String phrase) throws Exception {
        cloud.jengu.dbo.postgres.PgChangeFeed feed = new cloud.jengu.dbo.postgres.PgChangeFeed(
                provisioner.provision(cloud.jengu.dbo.tenant.TenantSpec.parse(
                        Files.readString(dir.resolve(tenant + ".json")))).dataSource(),
                cloud.jengu.dbo.policy.AuditModel.DOMAIN);
        String cursor = null;
        for (var chunk = feed.read(null, 200); !chunk.items().isEmpty();
                chunk = feed.read(cursor, 200)) {
            for (var item : chunk.items()) {
                if (new String(item.payload(), StandardCharsets.UTF_8).contains(phrase)) {
                    return true;
                }
            }
            if (chunk.nextCursor() == null || chunk.nextCursor().equals(cursor)) {
                return false;
            }
            cursor = chunk.nextCursor();
        }
        return false;
    }

    /**
     * The carrier form: what crosses a boundary without being disclosed to
     * whatever carries it.
     */
    @Test
    void encryptedHandsBackTheCiphertext() {
        Disclosure.set(Disclosure.Mode.ENCRYPTED);
        String read = read();
        assertTrue(read.contains("__pdiEnc"),
                "the ciphertext block is the point of this mode: " + read);
        assertFalse(read.contains("Salakas"), "and nothing here decrypts it: " + read);
    }

}
