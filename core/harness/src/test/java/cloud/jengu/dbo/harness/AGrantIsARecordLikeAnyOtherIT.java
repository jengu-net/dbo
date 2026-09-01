package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
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
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changing who may do what is an ordinary write, and leaves the ordinary
 * traces: an audit entry, a feed item, and a line in the backup.
 *
 * <p>What made this worth asserting is what "authorization as records" is for.
 * A store whose permissions live in a side table has to grow a second audit
 * mechanism, a second export path and a second answer to "who changed this and
 * when" — and each of those is a place the two can disagree. Keeping grants as
 * records means the answer is the same machinery, which is only a saving if it
 * is actually true.
 *
 * <p>It had been half-covered: a grant was written and the surface it gates
 * was checked, so the <i>effect</i> was proven. Nothing looked at the three
 * traces, which is the part that would rot quietly — a grant written through a
 * path that skips the policy decorator still gates correctly, and is invisible
 * to every one of them.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AGrantIsARecordLikeAnyOtherIT {

    private static final byte[] OWNER_KEY = new byte[32];
    private static final byte[] KEK = new byte[32];

    static PGSimpleDataSource ds;
    static PolicyObjectStore identity;
    static TenantAuthority authority;
    static List<TypeRegistration> types;

    @BeforeAll
    void up() {
        new SecureRandom().nextBytes(OWNER_KEY);
        new SecureRandom().nextBytes(KEK);

        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AGrantIsARecordLikeAnyOtherIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        types = new ArrayList<>(IdentityModel.registrations());
        types.addAll(AuditModel.registrations());

        // The decorator is the tenant's write discipline, and the authority
        // writes through it exactly as any other caller does. Handing the
        // authority the bare engine instead would be the defect this test
        // exists to notice.
        identity = new PolicyObjectStore(new PgObjectStore(ds, types),
                TenantPolicies.parse(Map.of("audit", Map.of("level", "writes"))));
        authority = new TenantAuthority(identity, "https://oigused.example/", new KeyProtector(KEK));
    }

    @Test
    @DisplayName("granting a role writes a record, and the record is the grant")
    @Proving(DboPromises.AUTH_ROLE_GRANTS_AS_RECORDS)
    void theGrantIsARecord() {
        Caller.set("tenant-admin");
        authority.ensureRoleGrant("oe", List.of("user/Patient.read", "user/Observation.read"));
        Caller.clear();

        // Each test grants its own role and looks for that one. Asserting a
        // count over a shared store makes a test that passes or fails by
        // running order, which is a worse defect than the one it was checking.
        List<String> grants = identity.select(Criteria.of("RoleGrant")).stream()
                .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                .filter(g -> g.contains("\"oe\""))
                .toList();

        assertEquals(1, grants.size(),
                "a grant that is not a record has nothing to audit, nothing to stream "
                        + "and nothing to export: " + grants);
        assertTrue(grants.get(0).contains("user/Patient.read"),
                "the record must carry the mapping it is: " + grants.get(0));
    }

    @Test
    @DisplayName("and the tenant's own trail says who changed who may do what")
    @Proving(DboPromises.AUTH_ROLE_GRANTS_AS_RECORDS)
    void theChangeIsInTheAuditTrail() {
        Caller.set("tenant-admin");
        authority.ensureRoleGrant("laborant", List.of("user/Specimen.read"));
        Caller.clear();

        List<String> entries = identity.select(Criteria.of("AuditEntry")).stream()
                .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                .filter(e -> e.contains("RoleGrant"))
                .toList();

        assertFalse(entries.isEmpty(),
                "no audit entry names a RoleGrant, so a change to who may do what is "
                        + "the one write nobody can account for afterwards");
        assertTrue(entries.stream().anyMatch(e -> e.contains("\"actor\":\"tenant-admin\"")),
                "the trail must name who granted it: " + entries);
    }

    @Test
    @DisplayName("a consumer watching the tenant's changes sees the grant go by")
    @Proving(DboPromises.AUTH_ROLE_GRANTS_AS_RECORDS)
    void theChangeIsOnTheFeed() {
        PgChangeFeed feed = new PgChangeFeed(ds, IdentityModel.DOMAIN);
        String cursor = feed.read(null, 500).nextCursor();

        Caller.set("tenant-admin");
        authority.ensureRoleGrant("apteeker", List.of("user/MedicationRequest.read"));
        Caller.clear();

        assertTrue(feed.read(cursor, 100).items().stream()
                        .anyMatch(i -> "RoleGrant".equals(i.typeName())),
                "the grant never reached the change feed, so anything keeping a "
                        + "derived copy of this tenant's authorization is now wrong and "
                        + "has no way to find out");
    }

    @Test
    @DisplayName("and a backup carries it, so a restored tenant is not one nobody may use")
    @Proving(DboPromises.AUTH_ROLE_GRANTS_AS_RECORDS)
    void theGrantRidesABackup() throws Exception {
        Caller.set("tenant-admin");
        authority.ensureRoleGrant("juhataja", List.of("user/*.read"));
        Caller.clear();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, IdentityModel.DOMAIN, OWNER_KEY, out, types,
                TenantExport.Kind.BACKUP, null);

        assertTrue(archiveText(out.toByteArray()).contains("juhataja"),
                "a backup without the grants restores a tenant whose people cannot do "
                        + "anything, and the operator has no way to see that until they try");
    }

    /** Everything in the sealed archive, as one string — we are asking whether it is in there. */
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
