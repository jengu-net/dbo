package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.maintenance.TenantImport;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.terminology.Concept;
import cloud.jengu.dbo.terminology.TerminologyStore;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive a customer leaves with can still read its own codes.
 *
 * <p>An export whose CodeSystems are absent is syntactically valid FHIR and
 * semantically unreadable: every coded value in it points at a definition
 * nobody has. That is why replicated terminology travels as a snapshot even
 * though it is not the customer's to own.
 *
 * <p>Two things had to be true at once for it to go missing, and the existing
 * proof could see neither. The portable element wrote the one domain it was
 * handed, and a tenant's definitions live in a domain of their own. And a
 * CodeSystem's concepts are not in that domain's records either — the record
 * is a shell and the concepts are rows in the native form, which is what makes
 * `$lookup` answer in the first place.
 *
 * <p>So this holds a real vocabulary rather than a type called Vocabulary: a
 * stand-in in the exported domain is carried by the code that was already
 * working, and says nothing about the two places the real one lives.
 *
 * <p>And it leaves whole. A CodeSystem is stored as a shell beside its
 * concepts, because a vocabulary kept as one document answers nothing — so an
 * archive carrying what is stored would carry a resource that resolves no
 * code. The face already knows how to put one back together for anything
 * leaving this store, which is what a grain codec is for and what replication
 * has used all along; the interchange element asks it the same question.
 *
 * <p>The internal element still carries the stored bytes, and that is not an
 * oversight: rewriting a payload there would change the bytes the version
 * chain and both signatures are over.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class APortableExportCarriesTheVocabularyIT {

    private static final String DOMAIN = "clinic";
    private static final String SYSTEM = "https://archivezone/cs/houses";
    private static final byte[] OWNER_KEY = new byte[32];
    private static final EnvelopeExtractor PLAIN = (typeName, payload) -> new Envelope();

    private static final List<TypeRegistration> TYPES = List.of(
            new TypeRegistration("Note", DOMAIN, IdentityClass.INTERNAL, Set.of(),
                    Handling.operational(), PLAIN, List.of()),
            new TypeRegistration("CodeSystem", Domains.DEFINITIONS, IdentityClass.CANONICAL,
                    Set.of(), Handling.replicated(), PLAIN, List.of()));

    static PGSimpleDataSource ds;

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        ds = database("portable_vocabulary");

        new PgObjectStore(ds, TYPES).put(PutRequest.create("Note",
                "{\"kind\":\"Note\",\"code\":\"gryffindor\"}".getBytes(StandardCharsets.UTF_8)));

        // The shell through the engine and the concepts into the native form,
        // which is what a bring-up does: written whole it would answer
        // nothing, so $lookup could not resolve the store's own vocabulary.
        new PgObjectStore(ds, TYPES).put(PutRequest.create("CodeSystem",
                        ("{\"resourceType\":\"CodeSystem\",\"url\":\"" + SYSTEM
                                + "\",\"version\":\"1\",\"content\":\"not-present\"}")
                                .getBytes(StandardCharsets.UTF_8)),
                Handling.Authority.SOURCE_TENANT);
        new TerminologyStore(ds).importSystem(SYSTEM, "1", List.of(
                new Concept("gryffindor", "Gryffindor", null, Map.of(), Map.of()),
                new Concept("slytherin", "Slytherin", null, Map.of(), Map.of())).iterator());
    }

    @Test
    @DisplayName("a portable export carries the code systems a tenant holds, wherever the "
            + "domain they live in happens to be")
    void theVocabularyTravels() throws Exception {
        Map<String, String> archive = contentsOf(exported());

        assertTrue(archive.keySet().stream().anyMatch(e -> e.endsWith("CodeSystem.ndjson")),
                "a portable export holds no CodeSystem at all, so every coded value in it "
                        + "points at a definition nobody has: " + new TreeSet<>(archive.keySet()));

        String rendered = archive.entrySet().stream()
                .filter(e -> e.getKey().startsWith("fhir/") && e.getKey().endsWith("CodeSystem.ndjson"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        assertTrue(rendered.contains(SYSTEM),
                "the code system this tenant holds is not in the interchange element: " + rendered);
        assertTrue(archive.containsKey("state/CodeSystem.ndjson"),
                "the internal element skipped it too, so a restore has nothing to put back: "
                        + new TreeSet<>(archive.keySet()));

        assertTrue(rendered.contains("gryffindor") && rendered.contains("slytherin"),
                "the CodeSystem left as the shell it is stored as, so it is well-formed FHIR "
                        + "that resolves nothing — the face knows how to make one whole for "
                        + "anything leaving the store, and this did not ask: " + rendered);
        assertTrue(archive.get("state/CodeSystem.ndjson").contains("not-present"),
                "the internal element carries something other than the stored bytes, which the "
                        + "version chain and both signatures are over");
    }

    @Test
    @DisplayName("and a restore puts the concepts back where they answer from, so the tenant "
            + "that received the archive can resolve its own codes")
    void theVocabularyIsRestored() throws Exception {
        // A fresh store, the way a tenant receiving an archive is fresh.
        PGSimpleDataSource fresh = database("portable_vocabulary_restored");
        ObjectStore target = new PgObjectStore(fresh, TYPES);
        byte[] sealed = exported();

        java.security.KeyPair vendor = java.security.KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        java.security.KeyPair tenant = java.security.KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        String root;
        try (InputStream plain = SealedArchive.opening(
                new ByteArrayInputStream(sealed), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            root = rootFrom(zip);
        }
        ArchiveAttestation attestation = ArchiveAttestation.over(root)
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                .signedBy(ArchiveAttestation.Party.TENANT, tenant.getPrivate().getEncoded());

        TenantImport.importVerified(target, () -> new ByteArrayInputStream(sealed),
                OWNER_KEY, attestation, vendor.getPublic().getEncoded(),
                tenant.getPublic().getEncoded(), TenantImport.HistoryMode.FRESH,
                accepted -> { }, TenantImport.comparingBytes(), keepingInto(fresh));

        assertTrue(new TerminologyStore(fresh).validateCode(SYSTEM, "gryffindor"),
                "the restored tenant holds the CodeSystem and cannot answer a code with it, "
                        + "which is the whole of what a vocabulary is for");
    }

    private static byte[] exported() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, DOMAIN, OWNER_KEY, out, TYPES,
                TenantExport.Kind.PORTABLE_EXPORT,
                (payload, id, versionId) -> new String(payload, StandardCharsets.UTF_8)
                        .replaceFirst("\\{", "{\"id\":\"" + id + "\","),
                REASSEMBLING);
        return out.toByteArray();
    }

    /**
     * A grain codec standing in for the face's.
     *
     * <p>What is under test here is that the export ASKS — the real
     * reassembly, tree and all, is a face's and is proven where it lives. A
     * stand-in that read nothing would prove the call and not that a concept
     * this tenant holds comes back, so it reads the native form the way the
     * face does.
     */
    private static final cloud.jengu.dbo.core.face.GrainCodec REASSEMBLING =
            new cloud.jengu.dbo.core.face.GrainCodec() {
                @Override
                public boolean handles(String typeName) {
                    return "CodeSystem".equals(typeName);
                }

                @Override
                public byte[] forTransport(String typeName, byte[] storedPayload) {
                    StringBuilder concepts = new StringBuilder();
                    for (Concept concept : new TerminologyStore(ds).allConcepts(SYSTEM)) {
                        concepts.append(concepts.isEmpty() ? "" : ",")
                                .append("{\"code\":\"").append(concept.code())
                                .append("\",\"display\":\"").append(concept.display())
                                .append("\"}");
                    }
                    return new String(storedPayload, StandardCharsets.UTF_8)
                            .replace("\"content\":\"not-present\"",
                                    "\"content\":\"complete\",\"concept\":[" + concepts + "]")
                            .getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public byte[] storedFormOf(String typeName, byte[] transportedPayload) {
                    return transportedPayload;
                }

                @Override
                public void keep(String typeName, byte[] transportedPayload) {
                }
            };

    private static Map<String, String> contentsOf(byte[] sealed) throws Exception {
        Map<String, String> entries = new TreeMap<>();
        try (InputStream plain = SealedArchive.opening(new ByteArrayInputStream(sealed), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** The archive's own root digest, which both parties sign. */
    private static String rootFrom(ZipInputStream zip) throws Exception {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if ("digests.json".equals(entry.getName())) {
                String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                int at = json.indexOf("\"root\":\"") + "\"root\":\"".length();
                return json.substring(at, json.indexOf('"', at));
            }
        }
        throw new IllegalStateException("no digest list in the archive");
    }

    /** The destination half of the same codec: concepts into the native form. */
    private static cloud.jengu.dbo.core.face.GrainCodec keepingInto(PGSimpleDataSource into) {
        return new cloud.jengu.dbo.core.face.GrainCodec() {
            @Override
            public boolean handles(String typeName) {
                return "CodeSystem".equals(typeName);
            }

            @Override
            public byte[] forTransport(String typeName, byte[] storedPayload) {
                return storedPayload;
            }

            @Override
            public byte[] storedFormOf(String typeName, byte[] transportedPayload) {
                return transportedPayload;
            }

            @Override
            public void keep(String typeName, byte[] transportedPayload) {
                String json = new String(transportedPayload, StandardCharsets.UTF_8);
                java.util.List<Concept> concepts = new java.util.ArrayList<>();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\{\"code\":\"([^\"]+)\",\"display\":\"([^\"]+)\"\\}")
                        .matcher(json);
                while (m.find()) {
                    concepts.add(new Concept(m.group(1), m.group(2), null, Map.of(), Map.of()));
                }
                new TerminologyStore(into).importSystem(SYSTEM, "1", concepts.iterator());
            }
        };
    }

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("APortableExportCarriesTheVocabularyIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + name);
        }
        PGSimpleDataSource target = new PGSimpleDataSource();
        target.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + name);
        target.setUser(SharedPostgres.get().getUsername());
        target.setPassword(SharedPostgres.get().getPassword());
        return target;
    }
}
