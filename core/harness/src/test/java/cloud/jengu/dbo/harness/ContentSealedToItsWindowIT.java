package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A device's message, whose person nobody can tell, kept sealed to the window
 * its kind declares rather than to nobody or in the clear.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentSealedToItsWindowIT {

    private static final String KIND = "device-message";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final byte[] MESSAGE = ("OBX|1|NM|8867-4^Heart rate||72|/min|||||F|"
            + "ContentSealedToItsWindowIT").getBytes(StandardCharsets.UTF_8);

    private final SharedTenants.Tenant tenant = SharedTenants.of(SharedTenants.Shape.R4_PDI_PERSON);
    private String key;

    @AfterAll
    void giveItBack() {
        SharedTenants.retire(tenant);
    }

    @Test
    @Order(1)
    @DisplayName("a device's message naming nobody is held sealed under its kind's key for the "
            + "day it was put, and read back as it was sent")
    @Proving(DboPromises.POL_CONTENT_IS_SEALED_TO_ITS_WINDOW)
    void contentNamingNobodyIsSealedToItsWindow() throws Exception {
        HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(
                        URI.create(tenant.base() + "/blob?kind=" + KIND))
                        .header("Content-Type", "text/plain")
                        .header("Authorization", "Bearer " + writer())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(MESSAGE)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, put.statusCode(), put.body());
        key = Extracted.field(put.body(), "key");

        HttpResponse<byte[]> read = get();
        assertEquals(200, read.statusCode());
        assertArrayEquals(MESSAGE, read.body(), "the message came back changed");

        try (Connection c = database();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content, sealed_window FROM state.blob WHERE key = ?::uuid")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the message is not held");
                assertTrue(!new String(rs.getBytes(1), StandardCharsets.ISO_8859_1)
                                .contains("ContentSealedToItsWindowIT"),
                        "the message is held in the clear");
                assertEquals(LocalDate.now(ZoneOffset.UTC), rs.getObject(2, LocalDate.class),
                        "the message is not sealed under the day it was put");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("once the window has passed for the day a message was put, its key is "
            + "destroyed, and a copy the sweep never removed answers that its window passed")
    @Proving(DboPromises.POL_CONTENT_IS_SEALED_TO_ITS_WINDOW)
    void destroyingTheKeyEndsWhatItSealed() throws Exception {
        LocalDate day;
        try (Connection c = database()) {
            // The key's day and the message's moved a month back, and not when
            // it was put: the row stands for a copy no sweep of rows reaches —
            // a backup, a replica — and only the key's destruction can end.
            try (PreparedStatement ps = c.prepareStatement("UPDATE pdi.window_key SET "
                    + "day = day - 30 WHERE kind = ? RETURNING day")) {
                ps.setString(1, KIND);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "no key was made for the kind");
                    day = rs.getObject(1, LocalDate.class);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE state.blob SET "
                    + "sealed_window = sealed_window - 30 WHERE key = ?::uuid")) {
                ps.setString(1, key);
                assertEquals(1, ps.executeUpdate());
            }
        }
        assertEquals(200, get().statusCode(), "the message could not be read under its key");

        tenant.retentionOnce();
        HttpResponse<byte[]> gone = get();
        assertEquals(410, gone.statusCode(), "a message sealed under a passed window still opens");
        assertTrue(new String(gone.body(), StandardCharsets.UTF_8).contains("window"),
                new String(gone.body(), StandardCharsets.UTF_8));
        String trail = tenant.engine().select(Criteria.of("AuditEntry")).stream()
                .map(entry -> new String(entry.payload(), StandardCharsets.UTF_8))
                .filter(entry -> entry.contains("retention-key-destroyed"))
                .reduce("", String::concat);
        assertTrue(trail.contains(KIND + "/" + day),
                "the key's destruction is not on the trail by kind and day: " + trail);
    }

    @Test
    @DisplayName("a tenant outside the membrane cannot declare content sealed to its window, "
            + "because it holds no key to seal with")
    @Proving(DboPromises.POL_CONTENT_IS_SEALED_TO_ITS_WINDOW)
    void onlyATenantHoldingKeysSealsToAWindow() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse("""
                        {"code":"sealsnothing","face":"r4",
                         "content":{"%s":{"keepAtPlace":"P1D","keepAtTenant":"P7D",
                                          "sealed":"window"}},
                         "types":[{"name":"Patient","identity":"internal",
                                   "handling":"operational"}]}""".formatted(KIND)));
        assertTrue(refused.getMessage().contains(KIND) && refused.getMessage().contains("pdi"),
                refused.getMessage());
    }

    private HttpResponse<byte[]> get() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(tenant.base() + "/blob/" + key))
                        .header("Authorization", "Bearer " + writer()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private String writer() {
        return tenant.token("content-sealed-to-its-window", "system/*.read", "system/*.write");
    }

    private Connection database() throws Exception {
        return DriverManager.getConnection(tenant.databaseUrl(),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
    }
}
