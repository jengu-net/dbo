package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.maintenance.SealedArchive;
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
 * <p><b>What this does not yet assert.</b> The CodeSystem that travels is the
 * shell the store holds — {@code content=not-present} — because its concepts
 * are rows in the native form. The store does know how to put them back: a
 * face's {@link cloud.jengu.dbo.core.face.GrainCodec#forTransport} reassembles
 * the stored form for anything leaving this store, and replication already
 * carries whole CodeSystems downstream that way. A portable export is the
 * other thing that leaves, and does not yet ask for it.
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
    }

    private static byte[] exported() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, DOMAIN, OWNER_KEY, out, TYPES,
                TenantExport.Kind.PORTABLE_EXPORT,
                (payload, id, versionId) -> new String(payload, StandardCharsets.UTF_8)
                        .replaceFirst("\\{", "{\"id\":\"" + id + "\","));
        return out.toByteArray();
    }

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
