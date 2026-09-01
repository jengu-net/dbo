package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.ZoneModel;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.policy.AuditModel;
import cloud.jengu.dbo.policy.PolicyObjectStore;
import cloud.jengu.dbo.policy.TenantPolicies;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A zone is a tenant, and what it declares are records — with the one thing
 * that must never be a record kept structurally out of them.
 *
 * <p>Zone declarations were written as setup in another test and asserted
 * about nowhere: the suite used brokers and identifier domains to arrange a
 * federation scenario and then said nothing about the declarations themselves.
 * That leaves the useful half unguarded, because "a zone's configuration is
 * ordinary records" is a claim about versioning, audit, export and streaming
 * rather than about whether federation works.
 *
 * <p>The clause worth having is the last one. A broker needs a client secret
 * to talk to its identity provider, and a broker declaration is
 * projected-config — it rides backups, exports and streams to dependants. Put
 * the secret in the record and it rides all of them too. So the secret arrives
 * from custody, and the declaration has nowhere to put one: this asserts the
 * absence structurally, on the type, rather than by grepping one payload and
 * hoping.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AZoneDeclaresItselfInRecordsIT {

    /**
     * The shapes a secret arrives wearing. Checking for a known secret VALUE
     * would be a test that cannot fail — nothing puts one in, so nothing finds
     * one — so what is checked is whether a place to put one has appeared.
     */
    private static final List<String> SECRET_BEARING =
            List.of("secret", "password", "credential", "privatekey", "token");

    private static final byte[] OWNER_KEY = new byte[32];

    static PGSimpleDataSource ds;
    static PolicyObjectStore zone;
    static List<TypeRegistration> types;

    @BeforeAll
    void up() {
        new SecureRandom().nextBytes(OWNER_KEY);
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AZoneDeclaresItselfInRecordsIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        types = new ArrayList<>(ZoneModel.registrations());
        types.addAll(AuditModel.registrations());
        zone = new PolicyObjectStore(new PgObjectStore(ds, types),
                TenantPolicies.parse(Map.of("audit", Map.of("level", "writes"))));
    }

    private static byte[] broker(String code, String issuer) {
        return ZoneModel.Broker.payload(new ZoneModel.Broker(
                code, issuer, "zone-rp", null, "high"));
    }

    @Test
    @DisplayName("a broker declaration has nowhere to put a secret, so no copy of it "
            + "can ever carry one")
    @Proving(DboPromises.ZONE_DECLARATIONS_AS_RECORDS)
    void theDeclarationCannotCarryASecret() {
        // The detector first, on a decoy: a scan that matches nothing would
        // report every payload clean, including one that had just started
        // carrying a client secret.
        assertEquals(List.of("clientSecret"),
                secretBearingKeysIn("{\"code\":\"x\",\"clientSecret\":\"hunter2\"}"),
                "the scan cannot see a secret-bearing key, so its silence means nothing");

        List<String> secretish = new ArrayList<>();
        for (RecordComponent c : ZoneModel.Broker.class.getRecordComponents()) {
            if (namesASecret(c.getName())) {
                secretish.add(c.getName());
            }
        }

        assertTrue(secretish.isEmpty(),
                "a broker declaration can hold a secret, and a declaration is "
                        + "projected-config: it rides backups, exports and every stream to a "
                        + "dependant, so the secret would ride them too — " + secretish);

        // And the written bytes agree with the type: a declaration for a broker
        // whose secret custody holds carries no trace of it.
        Caller.set("zone-admin");
        PutResult written = zone.put(PutRequest.create("ZoneBroker",
                broker("eeid", "https://eeid.example/")));
        Caller.clear();
        String payload = new String(zone.get("ZoneBroker", written.id()).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        assertTrue(secretBearingKeysIn(payload).isEmpty(),
                "the written declaration carries a field a secret would go in: "
                        + secretBearingKeysIn(payload) + " in " + payload);
        assertTrue(payload.contains("https://eeid.example/"),
                "and the declaration must still say what it declares: " + payload);
    }

    @Test
    @DisplayName("changing a declaration versions it rather than replacing what was true before")
    @Proving(DboPromises.ZONE_DECLARATIONS_AS_RECORDS)
    void aChangedDeclarationIsVersioned() {
        Caller.set("zone-admin");
        PutResult first = zone.put(PutRequest.create("ZoneBroker",
                broker("tara", "https://tara.example/v1")));
        zone.put(PutRequest.update("ZoneBroker", first.id(), 1,
                broker("tara", "https://tara.example/v2")));
        Caller.clear();

        List<StoredObject> history = zone.history("ZoneBroker", first.id());
        assertEquals(2, history.size(),
                "a declaration that overwrites cannot answer what this zone told its "
                        + "dependants last month");
        assertTrue(zone.select(Criteria.of("AuditEntry")).stream()
                        .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                        .anyMatch(e -> e.contains("ZoneBroker")),
                "no audit entry names the declaration, so a zone's configuration changes "
                        + "with nobody accountable for them");
    }

    @Test
    @DisplayName("declarations stream and export down the same chains as any content")
    @Proving(DboPromises.ZONE_DECLARATIONS_AS_RECORDS)
    void declarationsTravelLikeContent() throws Exception {
        PgChangeFeed feed = new PgChangeFeed(ds, ZoneModel.DOMAIN);
        String cursor = feed.read(null, 500).nextCursor();

        Caller.set("zone-admin");
        zone.put(PutRequest.create("ZoneIdentifierDomain",
                ("{\"use\":\"national\",\"status\":\"active\","
                        + "\"system\":\"https://ee.ee/eid\"}").getBytes(StandardCharsets.UTF_8)));
        Caller.clear();

        assertTrue(feed.read(cursor, 100).items().stream()
                        .anyMatch(i -> "ZoneIdentifierDomain".equals(i.typeName())),
                "a declaration that is not on the feed cannot reach a dependent tenant, "
                        + "which is the whole point of declaring it in a zone");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, ZoneModel.DOMAIN, OWNER_KEY, out, types,
                TenantExport.Kind.BACKUP, null);
        String archive = archiveText(out.toByteArray());

        assertTrue(archive.contains("https://ee.ee/eid"),
                "a backup without the zone's declarations restores a zone that declares "
                        + "nothing, and its dependants find out one by one");
        assertTrue(secretBearingKeysIn(archive).isEmpty(),
                "the archive carries a secret-bearing field, and an archive is the copy "
                        + "that travels furthest and lives longest: "
                        + secretBearingKeysIn(archive));
    }

    private static boolean namesASecret(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace("_", "");
        return SECRET_BEARING.stream().anyMatch(lower::contains);
    }

    /** Every JSON key in the text that a secret could plausibly be filed under. */
    private static List<String> secretBearingKeysIn(String json) {
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:").matcher(json);
        while (m.find()) {
            if (namesASecret(m.group(1)) && !found.contains(m.group(1))) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    private static String archiveText(byte[] sealed) throws Exception {
        StringBuilder all = new StringBuilder();
        try (InputStream plain = SealedArchive.opening(new ByteArrayInputStream(sealed), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                all.append(entry.getName()).append('\n')
                        .append(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                        .append('\n');
            }
        }
        return all.toString();
    }
}
