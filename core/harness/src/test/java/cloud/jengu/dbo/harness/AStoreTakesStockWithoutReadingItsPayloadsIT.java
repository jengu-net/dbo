package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Held;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An inventory says what a store holds of a type — every live object, its
 * identifiers, and the envelope values asked for — without reading a single
 * payload. It is what a face takes stock of its definitions with, and two
 * thousand definitions are tens of megabytes it would otherwise read to
 * learn their names.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AStoreTakesStockWithoutReadingItsPayloadsIT {

    static PgObjectStore store;
    static List<FaceRootPackages.Definition> definitions;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AStoreTakesStockWithoutReadingItsPayloadsIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        var declared = R4FhirVersion.INSTANCE.forTypes(List.of(
                FhirTypeConfig.canonical("StructureDefinition")));
        store = new PgObjectStore(ds, declared.registrations());
        definitions = FaceRootPackages.definitionsFor("r4", Set.of("StructureDefinition")).stream()
                .filter(d -> d.url().endsWith("/Patient") || d.url().endsWith("/Observation")
                        || d.url().endsWith("/vitalsigns"))
                .toList();
        assertEquals(3, definitions.size());
    }

    @Test
    @DisplayName("the inventory names every live object by its identifiers and the values asked for, and not the deleted one")
    void theInventoryNamesEveryLiveObject() {
        List<PutRequest> writes = definitions.stream()
                .map(d -> new PutRequest("StructureDefinition", UUID.randomUUID().toString(), null, d.document()))
                .toList();
        store.transact(writes);
        String observation = writes.stream().filter(w -> new String(w.payload()).contains("/StructureDefinition/Observation\""))
                .findFirst().orElseThrow().id();
        store.delete("StructureDefinition", observation, null);

        List<Held> held = store.inventory("StructureDefinition", List.of("derivation", "kind", "nonesuch"));

        Map<String, Held> byUrl = held.stream().collect(Collectors.toMap(
                h -> h.identifiers().stream().filter(i -> Identifier.CANONICAL_SYSTEM.equals(i.system()))
                        .map(Identifier::value).findFirst().orElse("?"), h -> h));
        assertEquals(Set.of("http://hl7.org/fhir/StructureDefinition/Patient",
                "http://hl7.org/fhir/StructureDefinition/vitalsigns"), byUrl.keySet(),
                "the deleted one is listed, or a live one is not: " + byUrl.keySet());
        Held vitals = byUrl.get("http://hl7.org/fhir/StructureDefinition/vitalsigns");
        assertEquals(EnvelopeValue.token(null, "constraint"), vitals.firsts().get("derivation"));
        assertEquals(EnvelopeValue.token(null, "resource"), vitals.firsts().get("kind"));
        assertTrue(!vitals.firsts().containsKey("nonesuch"), "a path the envelope has no value under is absent, not null");
        assertEquals(1L, vitals.versionId());
        assertTrue(writes.stream().anyMatch(w -> w.id().equals(vitals.id())), "the id is the object's own");
    }
}
