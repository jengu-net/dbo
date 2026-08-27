package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the statement says about a type is what the store does with it (#52).
 *
 * <p>The document used to declare `create`, `update` and `delete` for every
 * configured type, whatever the type declared — so it told a client it could
 * create a replicated vocabulary, and the store answered 403. That cost real
 * time during #31, where a `CodeSystem` declared `replicated` refused a POST
 * while the statement said otherwise.
 *
 * <p>A generated document that misleads is worse than an absent one, because it
 * is trusted. So the claims are checked against behaviour rather than against
 * the code that produced them — asserting the generator agrees with itself would
 * prove nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityHonestyIT {

    private static final EnvelopeExtractor PLAIN = (type, payload) -> new Envelope();

    static PostgreSQLContainer<?> postgres;
    static R4Store store;
    static PgObjectStore engine;
    static String statement;

    /** One type per travel class, so the derivation is exercised rather than assumed. */
    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("CapabilityHonestyIT"));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());

        R4Personality personality = new R4Personality(List.of(
                // the tenant's own: everything allowed
                FhirTypeConfig.internal("Observation"),
                // published by a zone: readable here, never written here
                new FhirTypeConfig("CodeSystem", IdentityClass.CANONICAL, Set.of(),
                        Handling.replicated()),
                // the trail: appended, never altered or removed, by anyone
                new FhirTypeConfig("AuditEvent", IdentityClass.INTERNAL, Set.of(),
                        Handling.audit())));

        engine = new PgObjectStore(ds, personality.registrations());
        store = new R4Store(engine, personality, "http://127.0.0.1/fhir");
        statement = store.capabilityStatement("http://127.0.0.1/fhir");
    }

    /** The block the statement devotes to one type. */
    private static String blockFor(String type) {
        int at = statement.indexOf("\"type\":\"" + type + "\"");
        assertTrue(at > 0, type + " is not in the statement at all: " + statement);
        int next = statement.indexOf("{\"type\":\"", at);
        return next > 0 ? statement.substring(at, next) : statement.substring(at);
    }

    private static boolean declares(String type, String interaction) {
        return blockFor(type).contains("\"code\":\"" + interaction + "\"");
    }

    @Test
    @Timeout(300)
    @DisplayName("a type the tenant may not write does not advertise create, and would refuse one")
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void aReplicatedTypeDoesNotAdvertiseWrites() {
        assertFalse(declares("CodeSystem", "create"),
                "the statement offers a create the store answers with 403");
        assertFalse(declares("CodeSystem", "update"), blockFor("CodeSystem"));
        assertFalse(declares("CodeSystem", "delete"), blockFor("CodeSystem"));
        assertTrue(declares("CodeSystem", "read"), blockFor("CodeSystem"));

        // and the behaviour it now matches: the shield refuses the tenant's write
        assertThrows(cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> engine.put(PutRequest.create("CodeSystem",
                        "{\"resourceType\":\"CodeSystem\",\"url\":\"https://x.test/cs\",\"status\":\"active\"}"
                                .getBytes(StandardCharsets.UTF_8))),
                "the type is writable after all — then the statement was right and this test is wrong");
    }

    @Test
    @Timeout(300)
    @DisplayName("an append-only type advertises create but neither update nor delete")
    void anAppendOnlyTypeAdvertisesNoAlteration() {
        // audit() is written by the machinery, not by tenant users, so it
        // advertises no create either — but the point here is alteration
        assertFalse(declares("AuditEvent", "update"),
                "the statement offers an update on a record nobody may alter: "
                        + blockFor("AuditEvent"));
        assertFalse(declares("AuditEvent", "delete"), blockFor("AuditEvent"));
    }

    @Test
    @Timeout(300)
    @DisplayName("the tenant's own type advertises the writes it really accepts")
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void anOperationalTypeAdvertisesItsWrites() {
        assertTrue(declares("Observation", "create"), blockFor("Observation"));
        assertTrue(declares("Observation", "update"), blockFor("Observation"));
        assertTrue(declares("Observation", "delete"), blockFor("Observation"));

        // and it accepts one
        store.create("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]}}""");
    }

    @Test
    @Timeout(300)
    @DisplayName("conditional create is advertised only where there is an identity to key it on")
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void conditionalCreateFollowsTheIdentityClass() {
        // a store-assigned id has nothing to key a condition on, and the store
        // refuses one — REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS
        assertTrue(blockFor("Observation").contains("\"conditionalCreate\":false"),
                blockFor("Observation"));
        assertThrows(IllegalArgumentException.class,
                () -> store.conditionalCreate("""
                        {"resourceType":"Observation","status":"final",
                         "code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]}}""",
                        Map.of("status", "final")),
                "a non-identity condition was accepted after all");
    }

    @Test
    @Timeout(300)
    @DisplayName("every meta parameter the statement declares is one the store accepts")
    @Proving({DboPromises.SRCH_HONEST_CAPABILITY, DboPromises.SRCH_STRICT_BY_DEFAULT})
    void declaredMetaParametersAreAccepted() {
        for (String name : List.of("_tag", "_profile", "_id", "_lastUpdated")) {
            assertTrue(blockFor("Observation").contains("\"name\":\"" + name + "\""),
                    name + " is accepted but undeclared: " + blockFor("Observation"));
            // accepted means it compiles rather than being refused as unknown.
            // _id takes this store's own id shape: a non-UUID raises rather
            // than returning no results, noted on #53.
            store.search("Observation", Map.of(name, switch (name) {
                case "_id" -> "01a01576-cf32-771c-a65c-e606c803d157";
                // a bare date, which is what a conformant client sends (#53)
                case "_lastUpdated" -> "2020-01-01";
                default -> "x";
            }), null);
        }

        // and one nobody accepts is refused rather than quietly widening the result
        assertThrows(cloud.jengu.dbo.fhir.common.UnknownSearchParameterException.class,
                () -> store.search("Observation", Map.of("_invented", "x"), null));
    }

    @Test
    @Timeout(120)
    @DisplayName("history is declared where it is kept, and the format is the one rendered")
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void historyAndFormatFollowTheDeclaration() {
        assertTrue(blockFor("Observation").contains("\"versioning\":\"versioned\""),
                blockFor("Observation"));
        assertTrue(blockFor("Observation").contains("\"readHistory\":true"),
                blockFor("Observation"));
        assertEquals(1, statement.split("\"application/fhir\\+json\"", -1).length - 1,
                "the statement declares a format the surface does not render: " + statement);
    }

    /** Registrations are what the engine enforces; the statement must not exceed them. */
    @Test
    @Timeout(120)
    @DisplayName("no type is declared that the store does not serve")
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void nothingIsDeclaredThatIsNotServed() {
        for (TypeRegistration registration : new R4Personality(List.of(
                FhirTypeConfig.internal("Observation"))).registrations()) {
            assertTrue(store.knowsType(registration.typeName()));
        }
        assertFalse(statement.contains("\"type\":\"Medication\""),
                "a type nothing registered is in the statement");
    }
}
