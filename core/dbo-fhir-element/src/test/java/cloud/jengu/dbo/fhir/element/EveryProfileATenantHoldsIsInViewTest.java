package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Held;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant holding its version as records holds thousands of profiles, and
 * the view used to be built from the first five hundred: the six hundredth
 * was in the store, claimed by a document, and refused as a profile the
 * tenant did not have.
 */
class EveryProfileATenantHoldsIsInViewTest {

    private static final int HELD = 600;
    private static final String PROFILE = "http://example.org/fhir/StructureDefinition/held-";

    @Test
    @DisplayName("the six hundredth profile a tenant holds is enforced like the first")
    void theSixHundredthProfileIsEnforcedLikeTheFirst() {
        Holding store = new Holding(profiles());
        ElementStore fhir = new ElementStore(store, ElementVersion.of("r4"),
                List.of(FhirTypeConfig.internal("Patient"), FhirTypeConfig.canonical("StructureDefinition")),
                "https://dbo.test/fhir", cloud.jengu.dbo.core.process.Steps.of(), Terms.NONE);
        String claiming = "{\"resourceType\":\"Patient\",\"meta\":{\"profile\":[\"" + PROFILE + HELD + "\"]}";
        fhir.create(claiming + ",\"gender\":\"female\"}");
        ValidationFailedException refused = assertThrows(ValidationFailedException.class,
                () -> fhir.create(claiming + "}"));
        assertTrue(refused.getMessage().contains("gender"),
                "refused for some reason other than the profile it claims: " + refused.getMessage());
    }

    /** {@code HELD} profiles on Patient, each requiring a gender, each with its own url. */
    private static List<byte[]> profiles() {
        byte[] patient = FaceRootPackages.definitionsFor("r4", Set.of("StructureDefinition")).stream()
                .filter(d -> d.url().equals("http://hl7.org/fhir/StructureDefinition/Patient"))
                .findFirst().orElseThrow().document();
        List<byte[]> out = new ArrayList<>();
        for (int i = 1; i <= HELD; i++) {
            try {
                JsonObject sd = JsonParser.parseObject(new ByteArrayInputStream(patient));
                sd.set("id", "held-" + i);
                sd.set("url", PROFILE + i);
                sd.set("name", "Held" + i);
                sd.set("derivation", "constraint");
                sd.set("baseDefinition", "http://hl7.org/fhir/StructureDefinition/Patient");
                for (JsonObject element : sd.getJsonObject("snapshot").getJsonArray("element").asJsonObjects()) {
                    if ("Patient.gender".equals(element.asString("path"))) {
                        element.set("min", 1);
                    }
                }
                out.add(JsonParser.compose(sd).getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return out;
    }

    /** Stands in for the engine with a shelf of profiles, paged as the engine pages. */
    private static final class Holding implements ObjectStore {
        private final List<StoredObject> profiles = new ArrayList<>();
        /** The canonical each one is held under, which a StoredObject does not carry. */
        private final java.util.Map<String, String> canonicals = new java.util.HashMap<>();

        Holding(List<byte[]> documents) {
            int i = 0;
            for (byte[] document : documents) {
                String id = "held-" + (++i);
                profiles.add(new StoredObject(id, "StructureDefinition", 1L, Instant.now(),
                        document, false, "4.0", null, null, List.of()));
                canonicals.put(id, PROFILE + i);
            }
        }

        @Override
        public FeedChunk<StoredObject> page(Criteria criteria, String cursor) {
            // BY TYPE. A shelf that hands back StructureDefinitions when asked
            // for StructureMaps is not a store, and this one did: the view was
            // being filled by the read for CONVERTERS, so the profile path
            // this test exists to hold could return nothing and the test still
            // passed. It stopped passing the moment the serving view stopped
            // loading maps, which is how the accident was found.
            List<StoredObject> of = profiles.stream()
                    .filter(p -> p.typeName().equals(criteria.typeName())).toList();
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            int to = Math.min(of.size(), from + criteria.limitValue());
            return new FeedChunk<>(of.subList(from, to), Integer.toString(to), to == of.size());
        }

        /** What the view is built from, which is the path under test. */
        @Override
        public List<Held> inventory(String typeName, List<String> paths) {
            List<Held> out = new ArrayList<>();
            for (StoredObject held : profiles) {
                if (held.typeName().equals(typeName)) {
                    out.add(new Held(held.id(), held.versionId(),
                            List.of(new Identifier(Identifier.CANONICAL_SYSTEM,
                                    canonicals.get(held.id()))),
                            java.util.Map.of()));
                }
            }
            return out;
        }

        @Override
        public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
            return profiles.stream().filter(p -> identifiers.stream().anyMatch(i ->
                    new String(p.payload(), StandardCharsets.UTF_8).contains("\"url\" : \"" + i.value() + "\""))).toList();
        }

        @Override
        public PutResult put(PutRequest request) {
            return new PutResult("id", 1L, true);
        }

        @Override
        public PutResult put(PutRequest request, Handling.Authority caller) {
            return put(request);
        }

        @Override
        public PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
            return put(request);
        }

        @Override
        public PutResult putConditional(IdentityRef identity, PutRequest request) {
            return put(request);
        }

        @Override
        public PutResult putConditional(IdentityRef identity, PutRequest request,
                Handling.Authority caller) {
            return put(request);
        }

        @Override
        public Optional<StoredObject> get(String typeName, String id) {
            return profiles.stream()
                    .filter(p -> p.typeName().equals(typeName) && p.id().equals(id))
                    .findFirst();
        }

        @Override
        public void delete(String typeName, String id, Long expectedVersion) {
        }

        @Override
        public void delete(String typeName, String id, Long expectedVersion, Handling.Authority caller) {
        }

        @Override
        public List<StoredObject> history(String typeName, String id) {
            return List.of();
        }

        @Override
        public List<StoredObject> select(Criteria criteria) {
            return List.of();
        }

        @Override
        public long count(Criteria criteria) {
            return 0;
        }

        @Override
        public int rebuildEnvelopes(String typeName) {
            return 0;
        }

        @Override
        public cloud.jengu.dbo.core.api.TypeRegistration registrationOf(String typeName) {
            throw new UnsupportedOperationException("a shelf, not a registry");
        }

        @Override
        public java.util.Collection<cloud.jengu.dbo.core.api.TypeRegistration> registrations() {
            throw new UnsupportedOperationException("a shelf, not a registry");
        }

        @Override
        public int reindexUnder(cloud.jengu.dbo.core.api.TypeRegistration replacement) {
            throw new UnsupportedOperationException("a shelf, not a registry");
        }
    }
}
