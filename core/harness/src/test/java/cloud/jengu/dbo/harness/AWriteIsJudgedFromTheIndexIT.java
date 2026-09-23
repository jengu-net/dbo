package cloud.jengu.dbo.harness;

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
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write judged from the index, through the door a caller uses.
 *
 * <p>The move item 025's critical path never wrote down. Everything the item
 * built — the index, the checks, the rules, the envelope, a face meeting the
 * whole payload contract — was held against the database's own answer and
 * agreed, and none of it had ever decided a write. Step 11's property cannot
 * hold until that stops being true: a node still parsing every write into an
 * element model still needs the definition packages, whatever else it can do.
 *
 * <p><b>Two seams, or neither.</b> A write reads its payload and builds its
 * envelope, and they are different objects behind different interfaces. The
 * first attempt swapped the payloads alone and every write still reached for a
 * worker context through the extractor — which a real write said immediately
 * and no comparison ever had, because a comparison calls a checker and a write
 * calls a store.
 *
 * <p><b>A world of its own, and the dial is why.</b> The face is chosen by a
 * system property at this stage rather than by a declaration, so any tenant
 * whose payloads were first built inside this window would keep the index face
 * for the rest of the run — a shared tenant caught that way would leave a
 * neighbouring class quietly testing something else. The declaration that
 * makes this per-tenant is the decision this measurement exists to inform, and
 * when it is taken this class joins a shared world like the rest.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AWriteIsJudgedFromTheIndexIT {

    private static final String DIAL = "dbo.payloads.index";
    private static final String TENANT = "indeksitenant";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String restore;

    @BeforeAll
    void up() throws Exception {
        restore = System.getProperty(DIAL);
        System.setProperty(DIAL, "true");
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-index-face");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AWriteIsJudgedFromTheIndexIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // A FACE ROOT, because the index can only judge what the tenant holds
        // as rows and a tenant holding none judges nothing. That is
        // "unresolvable is not invalid" taken to its limit, and it is the
        // hazard a per-tenant declaration has to face: declaring this face on
        // a tenant with no definitions would accept everything, quietly. A
        // root loads the version and expands it, so there is something to
        // judge against.
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
    }

    @AfterAll
    void down() {
        if (restore == null) {
            System.clearProperty(DIAL);
        } else {
            System.setProperty(DIAL, restore);
        }
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @DisplayName("a tenant whose face reads the index stores a correct document, gives it back "
            + "unaltered, and refuses each of the three kinds of wrong")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void aWriteIsDecidedWithoutTheElementModel() {
        var tenant = manager.runtime(TENANT).orElseThrow();

        String id = tenant.store().create("""
                {"resourceType":"Patient","gender":"female",
                 "name":[{"family":"Vaartus","given":["Anu","Mari"]}]}""").id();
        assertNotNull(id, "a correct document was not stored");

        // What comes back is what went in. A face that wrote a number as a
        // string or dropped a repeat would pass every check and corrupt the
        // record, so this reads it back rather than trusting the accept.
        String back = tenant.store().read("Patient", id);
        assertTrue(back.contains("\"family\":\"Vaartus\""),
                "what was stored is not what was sent: " + back);
        assertTrue(back.contains("Anu") && back.contains("Mari"),
                "a repeat lost an entry: " + back);

        // And it is findable, which is the envelope's half of the write. A
        // face whose payloads were swapped and whose extractor was not would
        // store the document and index nothing — a search that finds nothing
        // and looks exactly like an answer.
        String found = tenant.store().search("Patient",
                java.util.Map.of("family", "vaartus"), null);
        assertTrue(found.contains(id),
                "the document was stored and not indexed, so nothing finds it: " + found);

        assertThrows(RuntimeException.class,
                () -> tenant.store().create("{\"resourceType\":\"Observation\"}"),
                "a document missing a required element was accepted");
        assertThrows(RuntimeException.class,
                () -> tenant.store().create(
                        "{\"resourceType\":\"Patient\",\"gender\":[\"female\",\"male\"]}"),
                "an element allowed once and sent twice was accepted — the defect this item "
                        + "found the toolchain committing in silence");
        assertThrows(RuntimeException.class,
                () -> tenant.store().create(
                        "{\"resourceType\":\"Patient\",\"gender\":\"kass\"}"),
                "a code outside a required binding was accepted");
    }
}
