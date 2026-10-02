package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.sync.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Erasure is asked at a door of its own, behind a token of its own.
 *
 * <p>No database here. What is claimed is the door's: who may ask, what must
 * be said, which tenant it will not touch, and that asking twice is asking
 * once. Whether a drop takes everything a tenant held is the provisioner's
 * claim, proven where there is a database to drop.
 */
class AnErasureHasADoorOfItsOwnTest {

    private static final String ERASE = "a-token-for-erasure";
    private static final String OPS = "a-token-for-reading-the-node";

    private final List<String> dropped = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private TenantRuntimeManager manager;

    @BeforeEach
    void up(@TempDir Path onDisk) {
        manager = new TenantRuntimeManager(onDisk, new Drops(), "127.0.0.1", 0, null)
                .declaredFrom(new DeclaresOne("still-declared"));
        manager.serveRuntimeState(OPS);
        manager.serveErasure(ERASE);
    }

    @AfterEach
    void down() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("an erasure is asked with the erasure token and a reason, and asking again "
            + "answers the same")
    @Proving(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN)
    void theHolderOfTheTokenErasesWithAReason() throws Exception {
        HttpResponse<String> first = erase("closed-clinic", ERASE, "{\"reason\":\"contract ended\"}");
        assertTrue(first.statusCode() == 200 && dropped.equals(List.of("closed-clinic")),
                "the holder of the erasure token, giving a reason, did not erase the tenant: "
                        + first.statusCode() + " " + first.body() + " dropped=" + dropped);
        HttpResponse<String> again = erase("closed-clinic", ERASE,
                "{\"reason\":\"contract ended\"}");
        assertTrue(again.statusCode() == 200 && again.body().equals(first.body()),
                "asking again for an erasure that already happened answered differently, so an "
                        + "operator whose first request timed out cannot simply ask again: "
                        + first.body() + " / " + again.statusCode() + " " + again.body());
    }

    @Test
    @DisplayName("the operator's own token reads the node and erases nothing")
    @Proving(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN)
    void theOperatorsTokenIsNotTheErasureToken() throws Exception {
        HttpResponse<String> withOps = erase("closed-clinic", OPS, "{\"reason\":\"tidying\"}");
        assertTrue(withOps.statusCode() == 401 && dropped.isEmpty(),
                "the token that reads what a node serves was enough to erase a tenant: "
                        + withOps.statusCode() + " " + withOps.body());
        assertEquals(401, erase("closed-clinic", null, "{\"reason\":\"x\"}").statusCode());
        HttpResponse<String> read = http.send(HttpRequest.newBuilder(URI.create(
                        root() + TenantRuntimeManager.ERASE_PATH + "/closed-clinic"))
                .header("Authorization", "Bearer " + ERASE).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, read.statusCode(), "erasure answered a GET: " + read.body());
        assertTrue(dropped.isEmpty(), "something was dropped: " + dropped);
    }

    @Test
    @DisplayName("an erasure without a reason is refused, and so is one of a tenant still "
            + "declared or of the management tenant")
    @Proving(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN)
    void whatTheDoorWillNotErase() throws Exception {
        HttpResponse<String> unsaid = erase("closed-clinic", ERASE, "{}");
        assertTrue(unsaid.statusCode() == 400 && unsaid.body().contains("reason"),
                "an erasure that gave no reason was not refused for it: " + unsaid.statusCode()
                        + " " + unsaid.body());
        HttpResponse<String> declared = erase("still-declared", ERASE,
                "{\"reason\":\"contract ended\"}");
        assertTrue(declared.statusCode() == 409 && declared.body().contains("still declared"),
                "a tenant still declared was not refused, so the next pass would bring it up "
                        + "again empty: " + declared.statusCode() + " " + declared.body());
        assertEquals(400, erase("Not A Code", ERASE, "{\"reason\":\"x\"}").statusCode());
        assertTrue(dropped.isEmpty(), "a refused erasure dropped something: " + dropped);
    }

    private HttpResponse<String> erase(String code, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root()
                        + TenantRuntimeManager.ERASE_PATH + "/"
                        + code.replace(" ", "%20")))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String root() {
        return "http://127.0.0.1:" + manager.port();
    }

    /** One tenant declared, by a source that says so and nothing else. */
    private record DeclaresOne(String code) implements ConfigSource {
        @Override
        public Fetch fetch() {
            return new Fetch(List.of(new ConfigApplication.Declared("tenant", code,
                    ("{\"code\":\"" + code + "\",\"face\":\"r4\",\"types\":[]}")
                            .getBytes(StandardCharsets.UTF_8))), "a-marker", true);
        }
    }

    /** A provisioner that only remembers what it was told to drop. */
    private final class Drops implements TenantDatabaseProvisioner {
        @Override
        public TenantDatabase provision(TenantSpec spec) {
            throw new UnsupportedOperationException("nothing here is brought up: " + spec.code());
        }

        @Override
        public void deprovision(String tenantCode) {
            dropped.add(tenantCode);
        }
    }
}
