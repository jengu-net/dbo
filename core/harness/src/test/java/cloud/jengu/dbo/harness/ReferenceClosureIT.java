package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.maintenance.ReferenceClosure;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jengu-platform#877: the platform can say whether its records are whole.
 *
 * <p>Nothing validates references on write, so a dangling reference is created
 * by ordinary operation and noticed by nobody. These prove it is noticed now —
 * and that the two kinds of absence stay apart, because a report full of
 * expected findings is a report nobody reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceClosureIT {

    private static final String DOMAIN = "closure";

    /** A payload of {"ref":"<type>/<id>"} becomes one reference edge. */
    private static final EnvelopeExtractor REFERENCING = (typeName, payload) -> {
        Envelope envelope = new Envelope();
        String json = new String(payload, StandardCharsets.UTF_8);
        int at = json.indexOf("\"ref\":\"");
        if (at >= 0) {
            String ref = json.substring(at + 7, json.indexOf('"', at + 7));
            int slash = ref.indexOf('/');
            envelope.reference("subject", ref.substring(0, slash), ref.substring(slash + 1));
        }
        return envelope;
    };

    static PGSimpleDataSource ds;
    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("ReferenceClosureIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE reference_closure");
        }
        ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "reference_closure");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        store = new PgObjectStore(ds, List.of(
                new TypeRegistration("Report", DOMAIN, IdentityClass.INTERNAL, Set.of(),
                        Handling.operational(), REFERENCING, List.of()),
                new TypeRegistration("Subject", DOMAIN, IdentityClass.INTERNAL, Set.of(),
                        Handling.operational(), REFERENCING, List.of())));
    }

    private static byte[] pointingAt(String reference) {
        return ("{\"ref\":\"" + reference + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(300)
    @DisplayName("#877: a record pointing at something absent is found, and named from both ends")
    void looseEndsAreFindable() {
        String missing = "11111111-1111-1111-1111-111111111111";
        PutResult report = store.put(PutRequest.create("Report",
                pointingAt("Subject/" + missing)));

        ReferenceClosure.Report result = ReferenceClosure.check(ds, DOMAIN, Set.of());

        assertFalse(result.isWhole());
        ReferenceClosure.LooseEnd end = result.looseEnds().stream()
                .filter(l -> l.ownerId().equals(report.id()))
                .findFirst().orElseThrow();
        assertEquals("Report", end.ownerType());
        assertEquals("Subject", end.targetType());
        assertEquals(missing, end.targetId());
        assertTrue(result.describe().contains(missing),
                "the description must name what is missing, or an operator cannot act on it");
    }

    @Test
    @Timeout(300)
    @DisplayName("#877: a reference resolved elsewhere by design is not reported as damage")
    void expectedAbsenceIsNotADefect() {
        store.put(PutRequest.create("Report",
                pointingAt("LicensedVocabulary/http://snomed.info/sct|73211009")));

        ReferenceClosure.Report strict = ReferenceClosure.check(ds, DOMAIN, Set.of());
        ReferenceClosure.Report aware =
                ReferenceClosure.check(ds, DOMAIN, Set.of("LicensedVocabulary"));

        assertTrue(strict.looseEnds().stream()
                        .anyMatch(l -> l.targetType().equals("LicensedVocabulary")),
                "without the declaration it is indistinguishable from damage");
        assertFalse(aware.looseEnds().stream()
                        .anyMatch(l -> l.targetType().equals("LicensedVocabulary")),
                "with it, a vocabulary the reader licenses themselves is not a defect");
        assertTrue(aware.absentByDesign().stream()
                        .anyMatch(l -> l.targetType().equals("LicensedVocabulary")),
                "but it is still reported, in its own column — silently dropping it would hide "
                        + "a real omission the day one happens");
    }

    @Test
    @Timeout(300)
    @DisplayName("#877: the report can be asked again, and a loose end heals when its target lands")
    void theReportIsRerunnableAndLooseEndsHeal() {
        String targetId = "22222222-2222-2222-2222-222222222222";
        store.put(PutRequest.create("Report", pointingAt("Subject/" + targetId)));

        long before = ReferenceClosure.check(ds, DOMAIN, Set.of()).looseEnds().stream()
                .filter(l -> l.targetId().equals(targetId)).count();
        assertEquals(1, before);

        // the missing record arrives — nothing is rewritten
        store.put(new PutRequest("Subject", targetId, null,
                "{\"who\":\"arrived late\"}".getBytes(StandardCharsets.UTF_8)));

        long after = ReferenceClosure.check(ds, DOMAIN, Set.of()).looseEnds().stream()
                .filter(l -> l.targetId().equals(targetId)).count();
        assertEquals(0, after,
                "a reference resolves by itself once its target is there — asking again is the "
                        + "whole value of a standing report");
    }

    @Test
    @Timeout(300)
    @DisplayName("#877: a restore that would leave records pointing at nothing is refused, not warned")
    void aRestoreIsWholeOrRefused() {
        store.put(PutRequest.create("Report",
                pointingAt("Subject/33333333-3333-3333-3333-333333333333")));

        ReferenceClosure.IncompleteRestoreException refused = assertThrows(
                ReferenceClosure.IncompleteRestoreException.class,
                () -> ReferenceClosure.requireWhole(ds, DOMAIN, Set.of()));

        assertTrue(refused.getMessage().contains("restore refused"), refused.getMessage());
        assertFalse(refused.report().isWhole());
        assertTrue(refused.report().referencesChecked() > 0,
                "a refusal that checked nothing would be a false alarm");
    }
}
