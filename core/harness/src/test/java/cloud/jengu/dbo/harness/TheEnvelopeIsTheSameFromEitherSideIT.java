package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index, built twice, and compared.
 *
 * <p>What a document can be found by is an envelope, and it has been built in
 * the JVM by evaluating thirty-odd expressions over an object tree on every
 * write. The parameters are compiled when they arrive, so the database can
 * build the same envelope over the document already in hand — which is the
 * point of doing any of this, and worth exactly nothing unless what it builds
 * is the same.
 *
 * <p><b>What is held here is the typed rules, one at a time and in full.</b>
 * Each kind produces a shape a search asks by, and those shapes are the
 * contract: three forms per coded value, a string folded and kept as written,
 * a date at the moment its span opens, a reference split into what it points
 * at. A rule that produces the wrong shape is a document that quietly stops
 * being findable, which reads as an empty result rather than as a fault.
 *
 * <p>What is NOT held here yet is identity with the extractor in force over a
 * corpus. The definition types are extracted by a narrow hand-written path of
 * their own rather than by these parameters, so comparing the two compares
 * different contracts; the corpus comparison belongs with ordinary resource
 * types, and those need documents this store does not carry.
 *
 * <p>Nothing reads the database's envelope yet. It is built beside the one in
 * force, and being right about the rules is what comes first.
 *
 * <p>What stays is the two legs that build a store in this process with a
 * database extractor named on its type. What the database extracts, and that
 * it loses nothing the engine stored, is walked in Rowling Land, in the
 * clinical story, against the root's own documents.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheEnvelopeIsTheSameFromEitherSideIT {

    static SharedTenants.Tenant tenant;
    static String ROOT;

    private static final ObjectMapper JSON = new ObjectMapper();


    @BeforeAll
    void up() throws Exception {
        // Shared. It compares what two sides make of the same document over
        // the version's own definitions, which is a question about the
        // version rather than about a tenant.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
        ROOT = tenant.code();
    }

    /**
     * The seam: a type may say its extractor lives in the database, and the
     * engine then asks the database rather than this process.
     *
     * <p>Registered canonically, which is what this corpus carries and what
     * the seam could not serve until the identity claim came from the function
     * too. The split is the same one the engine makes everywhere else: it says
     * WHICH types are identified by their own url, and the face's SQL says
     * WHAT that identity is — a url in a document whose type is not identified
     * that way is an ordinary value, and only the registration knows which is
     * which.
     *
     * <p>So the record is found afterwards BY that identity, which is the half
     * that says the claim was made and not merely that a row was written.
     */
    @Test
    @Timeout(300)
    @DisplayName("a type whose extractor is a database function is written without this process "
            + "extracting anything, and lands the same")
    void aDatabaseExtractorIsAskedInsteadOfThisProcess() throws Exception {
        int[] askedInJava = {0};
        List<cloud.jengu.dbo.core.api.TypeRegistration> registrations = new ArrayList<>();
        for (cloud.jengu.dbo.core.api.TypeRegistration one
                : new cloud.jengu.dbo.fhir.r4.R4Personality(List.of(
                        cloud.jengu.dbo.fhir.common.FhirTypeConfig.canonical("ValueSet")))
                .registrations()) {
            if (!"ValueSet".equals(one.typeName())) {
                registrations.add(one);
                continue;
            }
            cloud.jengu.dbo.core.api.EnvelopeExtractor inJava = one.extractor();
            cloud.jengu.dbo.core.api.DatabaseExtractor inTheDatabase =
                    new cloud.jengu.dbo.core.api.DatabaseExtractor() {
                        @Override
                        public String functionName() {
                            return "dbo.envelope_parts";
                        }

                        @Override
                        public cloud.jengu.dbo.core.api.Envelope extract(String typeName,
                                byte[] payload) {
                            askedInJava[0]++;
                            return inJava.extract(typeName, payload);
                        }
                    };
            registrations.add(new cloud.jengu.dbo.core.api.TypeRegistration(one.typeName(),
                    one.domain(), one.identityClass(), one.identitySystems(),
                    one.handling(), inTheDatabase, one.indexes(), one.payloadVersion()));
        }

        cloud.jengu.dbo.postgres.PgObjectStore store =
                new cloud.jengu.dbo.postgres.PgObjectStore(tenantSource(), registrations);
        byte[] declared = ("{\"resourceType\":\"ValueSet\",\"status\":\"active\","
                + "\"url\":\"https://seam.test/vs\",\"name\":\"Seam\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var written = store.put(cloud.jengu.dbo.core.api.PutRequest.create("ValueSet", declared));

        assertEquals(0, askedInJava[0],
                "the type says its extractor is a database function and this process extracted "
                        + "it anyway, which is the whole of what the seam is for");

        // And what landed is what the other side would have produced. Asked of
        // the row rather than of the function again, so this is what a reader
        // would actually be answered from.
        JsonNode stored;
        try (Connection c = tenantSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT envelope::text FROM %s_data WHERE id = ?"
                             .formatted(Domains.tables(Domains.DEFINITIONS)))) {
            ps.setObject(1, java.util.UUID.fromString(written.id()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                stored = JSON.readTree(rs.getString(1));
            }
        }
        assertTrue(stored.has("url"),
                "the database extracted nothing a search could ask by: " + stored);
        assertEquals("https://seam.test/vs", stored.get("url").get(0).get("v").asText(),
                "the url landed as something other than what was declared: " + stored);

        // And the claim: a canonical type is FOUND by its url, which is the
        // half a row in the envelope does not say. Without it the record is
        // written and unfindable by the one name it has.
        assertEquals(1, store.getByIdentifier("ValueSet",
                        List.of(new cloud.jengu.dbo.core.api.Identifier(
                                cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM,
                                "https://seam.test/vs"))).size(),
                "the database wrote the record and not the identity it is claimed under");
    }

    /**
     * The other half of the seam, and the one a passing test cannot show on
     * its own: that the Java extractor is not merely unnecessary but unused.
     *
     * <p>Counting calls would mean instrumenting the face to satisfy a test,
     * so the question is asked the other way round. The type names a function
     * that does not exist. If the engine were extracting here — or falling
     * back to here when the database refused — the write would succeed and
     * nobody would ever know the declaration had done nothing. It fails, and
     * the counting extractor beside it was never asked.
     *
     * <p>This is the assertion that caught the real defect: the declaration
     * was first wired onto the personalities, which is not what a served
     * tenant's registrations are built from, and every test passed while the
     * feature reached no tenant at all.
     */
    @Test
    @Timeout(300)
    @DisplayName("a type whose extractor names a function the database does not have is "
            + "refused, rather than quietly extracted here instead")
    void aMissingFunctionIsNotFallenBackFrom() throws Exception {
        int[] askedInJava = {0};
        List<cloud.jengu.dbo.core.api.TypeRegistration> registrations = new ArrayList<>();
        for (cloud.jengu.dbo.core.api.TypeRegistration one
                : new cloud.jengu.dbo.fhir.r4.R4Personality(List.of(
                        cloud.jengu.dbo.fhir.common.FhirTypeConfig.canonical("ValueSet")))
                .registrations()) {
            if (!"ValueSet".equals(one.typeName())) {
                registrations.add(one);
                continue;
            }
            cloud.jengu.dbo.core.api.EnvelopeExtractor inJava = one.extractor();
            registrations.add(new cloud.jengu.dbo.core.api.TypeRegistration(one.typeName(),
                    one.domain(), one.identityClass(), one.identitySystems(), one.handling(),
                    new cloud.jengu.dbo.core.api.DatabaseExtractor() {

                        @Override
                        public String functionName() {
                            return "dbo.no_such_extractor";
                        }

                        @Override
                        public cloud.jengu.dbo.core.api.Envelope extract(String typeName,
                                byte[] payload) {
                            askedInJava[0]++;
                            return inJava.extract(typeName, payload);
                        }
                    }, one.indexes(), one.payloadVersion()));
        }

        cloud.jengu.dbo.postgres.PgObjectStore store =
                new cloud.jengu.dbo.postgres.PgObjectStore(tenantSource(), registrations);
        byte[] declared = ("{\"resourceType\":\"ValueSet\",\"status\":\"active\","
                + "\"url\":\"https://seam.test/vs-missing\",\"name\":\"Missing\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class,
                () -> store.put(cloud.jengu.dbo.core.api.PutRequest.create("ValueSet", declared)),
                "the function does not exist and the write succeeded anyway, so something "
                        + "else computed the envelope and the declaration means nothing");
        assertEquals(0, askedInJava[0],
                "the database refused and this process quietly did the work instead, which is "
                        + "the fallback that would make a wrong declaration invisible");
    }

    // ------------------------------------------------------------- the two
    private static PGSimpleDataSource tenantSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
