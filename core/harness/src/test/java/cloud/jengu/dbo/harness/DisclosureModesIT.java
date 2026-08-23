package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Disclosure;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
             "name":[{"family":"Salakas"}]}""";

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
        Files.writeString(dir.resolve("kolm.json"), """
                {"code":"kolm","fhirVersion":"r4","pdi":true,
                 "audit":{"level":"full"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("kolm"));
        store = manager.runtime("kolm").orElseThrow().engine();
        personId = store.put(PutRequest.create("Patient",
                PATIENT.getBytes(StandardCharsets.UTF_8))).id();
    }

    @AfterEach
    void saidNothing() {
        Disclosure.clear();
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

        assertTrue(auditSays("kolm", "\"purpose\":\"ETREAT\""),
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
