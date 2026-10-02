package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.ArchiveManifest;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-VENDOR-CHANGE, walked in Rowling Land, the sample world.
 *
 * <p>The clinic is leaving. Whatever the reason, the question is the one every
 * provider should be able to ask before signing anything: <b>can I get
 * everything out, and can somebody else read it without asking you?</b>
 *
 * <p>Backup and export are one mechanism, so the thing that runs nightly is
 * the thing that leaves. The archive is sealed to a key the clinic holds, so
 * the party operating the store cannot read what it holds for them, and it
 * goes back in somewhere else only with both parties' signatures over it.
 * Everything here goes through the tenant's maintenance surface, the door an
 * operator uses.
 *
 * <p><b>Two clinics of its own, and why.</b> What leaves is a whole estate,
 * and a claim about a whole estate on a world every story writes to would be a
 * claim about every story's data. So this story declares the clinic that
 * leaves and the one that takes it in, under its own prefix, and retracts both.
 */
@AUserStory
class TheClinicChangesVendorIT {

    private static final byte[] THEIR_KEY = key();
    private static final byte[] SOMEBODY_ELSES_KEY = key();

    private final StoryNames names = StoryNames.of(DboStories.VENDOR_CHANGE);
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    DboTestContext dbo;

    private String here;
    private String elsewhere;
    private String mrn;
    private String liis;
    private byte[] archive;
    private Signed signed;

    @BeforeAll
    void theClinicAndItsNewVendor() {
        mrn = "urn:" + names.prefix() + ":mrn";
        here = names.tenant("here");
        elsewhere = names.tenant("elsewhere");
        for (String code : List.of(here, elsewhere)) {
            dbo.declare(code, """
                    {"code":"%s","face":"r4","audit":{"level":"none"},
                     "types":[
                      {"name":"Patient","identity":"identifier","systems":["%s"],
                       "handling":"operational"},
                      {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                    .formatted(code, mrn));
        }
        for (String code : List.of(here, elsewhere)) {
            assertTrue(dbo.until(code, true, Duration.ofMinutes(10)),
                    code + " never came up: " + dbo.serving());
        }

        var patient = dbo.write(here, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"49001010000"}],
                 "name":[{"family":"Tamm","given":["Liis"]}]}""".formatted(mrn));
        assertTrue(patient.accepted(), patient.body());
        liis = patient.idOrFail();
        var observation = dbo.write(here, "Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"VEND-1"}]},
                 "subject":{"reference":"Patient/%s"}}""".formatted(liis));
        assertTrue(observation.accepted(), observation.body());
    }

    @AfterAll
    void bothClinicsAreWithdrawn() {
        if (here != null) {
            dbo.retract(here);
            dbo.retract(elsewhere);
        }
    }

    // ── everything leaves as one sealed file ──

    @Test
    @Order(1)
    @DisplayName("the whole estate leaves as one archive, sealed to a key the clinic holds, "
            + "with nothing readable in the file by whoever is storing it")
    @Proving({DboPromises.MNT_BACKUP_IS_EXPORT, DboPromises.MNT_OWNER_KEY_ENCRYPTION})
    void everythingLeavesSealed() throws Exception {
        HttpResponse<byte[]> taken = admin(here, "archive", new byte[0],
                "X-Owner-Key", encoded(THEIR_KEY), "X-Archive-Kind", "portable-export");
        assertEquals(200, taken.statusCode(), new String(taken.body(), StandardCharsets.UTF_8));
        archive = taken.body();
        Proves.that(DboPromises.MNT_BACKUP_IS_EXPORT, archive.length > 0,
                "the export answered with nothing to take away");

        // The same operation runs nightly. An escape route exercised only on
        // the day somebody leaves is an escape route nobody has tested.
        String raw = new String(archive, StandardCharsets.ISO_8859_1);
        Proves.that(DboPromises.MNT_OWNER_KEY_ENCRYPTION,
                !raw.contains("Tamm") && !raw.contains("49001010000"),
                "a name or a record number is readable in the file the platform is storing, "
                        + "so the custodian can read what it holds");
        signed = Signed.over(archive, THEIR_KEY);
    }

    @Test
    @Order(2)
    @DisplayName("somebody else's key opens nothing, and the new vendor's store is left as it "
            + "was")
    @Proving(DboPromises.MNT_OWNER_KEY_ENCRYPTION)
    void somebodyElsesKeyOpensNothing() throws Exception {
        HttpResponse<byte[]> refused = importInto(elsewhere, signed, SOMEBODY_ELSES_KEY);
        Proves.that(DboPromises.MNT_OWNER_KEY_ENCRYPTION, refused.statusCode() != 200,
                "an archive opened with the wrong key was imported, so the key is a formality "
                        + "rather than the thing that protects the file: "
                        + new String(refused.body(), StandardCharsets.UTF_8));
        assertEquals(404, dbo.read(elsewhere, "Patient", liis).statusCode(),
                "a refused import left a record behind");
    }

    // ── and goes back in somewhere else ──

    @Test
    @Order(3)
    @DisplayName("it goes into a store the clinic's old vendor does not run, keeping the "
            + "identities the clinic's other systems already refer to")
    @Proving({DboPromises.MNT_PORTABLE_STATE_EXPORT, DboPromises.MNT_SNAPSHOT_CONSISTENT,
            DboPromises.CORE_REINDEX_IS_AN_OPERATION})
    void itGoesInElsewhereWithTheSameIdentities() throws Exception {
        HttpResponse<byte[]> imported = importInto(elsewhere, signed, THEIR_KEY);
        String answer = new String(imported.body(), StandardCharsets.UTF_8);
        assertEquals(200, imported.statusCode(), answer);
        Proves.that(DboPromises.MNT_SNAPSHOT_CONSISTENT, answer.contains("\"imported\":2"),
                "the estate was two records and the import did not carry both: " + answer);

        HttpResponse<String> restored = dbo.read(elsewhere, "Patient", liis);
        Proves.that(DboPromises.MNT_PORTABLE_STATE_EXPORT,
                restored.statusCode() == 200 && restored.body().contains("Tamm"),
                "the record did not come back readable, under the id it had, to the party "
                        + "holding the key: " + restored.statusCode() + " " + restored.body());

        // Searchable, which is the part that would be invisible if it were
        // wrong: projections are derived from the payload and rebuilt on the
        // way in, so an import is not a pile of documents nobody can find.
        HttpResponse<String> found = dbo.search(elsewhere, "Patient",
                "identifier=" + mrn + "|49001010000");
        Proves.that(DboPromises.CORE_REINDEX_IS_AN_OPERATION,
                dbo.says(found).at("entry.resource.id").equals(List.of(liis)),
                "she is not findable in the new store by the number her other systems know "
                        + "her by, so this was a dump rather than a move: " + found.body());
    }

    @Test
    @Order(4)
    @DisplayName("a portable import starts history fresh, because the new store did not "
            + "witness the edits it never saw")
    @Proving(DboPromises.MNT_HISTORY_BY_SCHEMA)
    void historyStartsFresh() {
        HttpResponse<String> restored = dbo.read(elsewhere, "Patient", liis);
        Proves.that(DboPromises.MNT_HISTORY_BY_SCHEMA,
                dbo.says(restored).one("meta.versionId").map("1"::equals).orElse(false),
                "a portable import claims a history the new store never saw: "
                        + restored.body());
    }

    @Test
    @Order(5)
    @DisplayName("importing the same archive twice changes nothing, because an import that "
            + "is not idempotent cannot be retried after a failure halfway")
    @Proving(DboPromises.MNT_PORTABLE_STATE_EXPORT)
    void importingTwiceChangesNothing() throws Exception {
        HttpResponse<byte[]> again = importInto(elsewhere, signed, THEIR_KEY);
        String answer = new String(again.body(), StandardCharsets.UTF_8);
        assertEquals(200, again.statusCode(), answer);
        // Skipped rather than rewritten: the estate also carries the tenant's
        // own authority records, so what is counted here is that nothing was
        // written, not how much was recognised.
        Proves.that(DboPromises.MNT_PORTABLE_STATE_EXPORT,
                answer.contains("\"imported\":0") && !answer.contains("\"skippedIdentical\":0"),
                "a retried import wrote something, so a failure halfway cannot be retried "
                        + "without leaving a trace: " + answer);
        Proves.that(DboPromises.MNT_PORTABLE_STATE_EXPORT,
                dbo.says(dbo.read(elsewhere, "Patient", liis)).one("meta.versionId")
                        .map("1"::equals).orElse(false),
                "a retried import added a version to what it had already brought");
    }

    // ── and the nightly backup is the same mechanism ──

    @Test
    @Order(6)
    @DisplayName("the clinic's nightly backup comes out of the same door, sealed the same way, "
            + "and restoring it gives back the estate as it was, history included")
    @Proving({DboPromises.MNT_BACKUP_IS_EXPORT, DboPromises.MNT_HISTORY_BY_SCHEMA})
    void theNightlyBackupRestores() throws Exception {
        HttpResponse<byte[]> taken = admin(here, "archive", new byte[0],
                "X-Owner-Key", encoded(THEIR_KEY), "X-Archive-Kind", "backup");
        assertEquals(200, taken.statusCode(), new String(taken.body(), StandardCharsets.UTF_8));
        byte[] backup = taken.body();
        // A backup that broke off after its headers went out is still a 200,
        // so what was answered is opened: a root that recomputes is a whole
        // archive, and a truncated one is not.
        Signed nightly;
        try {
            nightly = Signed.over(backup, THEIR_KEY);
        } catch (Exception broken) {
            throw new AssertionError("the backup that came out is not a whole archive ("
                    + backup.length + " bytes), so the nightly backup this clinic relies on "
                    + "cannot be opened: " + broken, broken);
        }
        String before = dbo.says(dbo.read(here, "Patient", liis)).one("meta.versionId")
                .orElseThrow();

        // Somebody edits her after the backup was taken.
        HttpResponse<String> edited = new ATenantsDoor(dbo, here).put("/Patient/" + liis, """
                {"resourceType":"Patient","id":"%s",
                 "identifier":[{"system":"%s","value":"49001010000"}],
                 "name":[{"family":"Kask","given":["Liis"]}]}""".formatted(liis, mrn));
        assertEquals(200, edited.statusCode(), edited.body());

        HttpResponse<byte[]> restored = admin(here, "restore", nightly.sealed(),
                "X-Owner-Key", encoded(THEIR_KEY),
                "X-Archive-Attestation", Base64.getEncoder().encodeToString(
                        nightly.attestation().toJson().getBytes(StandardCharsets.UTF_8)),
                "X-Vendor-Key", encoded(nightly.vendorKey()),
                "X-Tenant-Key", encoded(nightly.tenantKey()));
        Proves.that(DboPromises.MNT_BACKUP_IS_EXPORT, restored.statusCode() == 200,
                "the backup the clinic's door gave out would not go back in: "
                        + restored.statusCode() + " "
                        + new String(restored.body(), StandardCharsets.UTF_8));
        HttpResponse<String> after = dbo.read(here, "Patient", liis);
        Proves.that(DboPromises.MNT_HISTORY_BY_SCHEMA,
                after.body().contains("Tamm") && !after.body().contains("Kask")
                        && dbo.says(after).one("meta.versionId").equals(
                                java.util.Optional.of(before)),
                "the restore did not give back the estate as the backup held it, version "
                        + "and all: " + after.body());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** An archive both parties signed: the vendor that exported it and the clinic. */
    private record Signed(byte[] sealed, ArchiveAttestation attestation, byte[] vendorKey,
            byte[] tenantKey) {

        static Signed over(byte[] sealed, byte[] ownerKey) throws Exception {
            String root = ArchiveManifest.rootOfSealed(new ByteArrayInputStream(sealed), ownerKey);
            KeyPair vendor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            KeyPair tenant = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            ArchiveAttestation attestation = ArchiveAttestation.over(root)
                    .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                    .signedBy(ArchiveAttestation.Party.TENANT, tenant.getPrivate().getEncoded());
            return new Signed(sealed, attestation, vendor.getPublic().getEncoded(),
                    tenant.getPublic().getEncoded());
        }
    }

    /** Imports a signed archive through the tenant's maintenance surface. */
    private HttpResponse<byte[]> importInto(String tenant, Signed archive, byte[] ownerKey)
            throws Exception {
        return admin(tenant, "import", archive.sealed(),
                "X-Owner-Key", encoded(ownerKey),
                "X-Archive-Attestation", Base64.getEncoder().encodeToString(
                        archive.attestation().toJson().getBytes(StandardCharsets.UTF_8)),
                "X-Vendor-Key", encoded(archive.vendorKey()),
                "X-Tenant-Key", encoded(archive.tenantKey()));
    }

    /** A POST to the tenant's maintenance surface, as the operator makes it. */
    private HttpResponse<byte[]> admin(String tenant, String operation, byte[] body,
            String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(dbo.at(tenant) + "/admin/" + operation))
                .header("Authorization", "Bearer " + dbo.token(tenant))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String encoded(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
