package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Held;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.hl7.fhir.r5.elementmodel.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant that holds its version as records answers a binding from them:
 * the value set from the record, the code system from the native form the
 * tenant took it apart into. A primitive {@code code} arrives at the
 * toolchain with no system at all, and the toolchain, holding no code
 * system, would accept anything — so the value set's own systems are asked
 * of the tenant's terminology, and a code none of them has is refused by
 * name. A value set none of whose systems the tenant holds stays what it
 * always was: unresolvable, never invalid.
 */
class ABindingIsAnsweredFromRecordsTest {

    private static final String GENDER = "http://hl7.org/fhir/administrative-gender";

    @Test
    @DisplayName("a bare code outside a required binding is refused by name; one inside it is accepted")
    @Proving(DboPromises.TERM_BINDINGS_ANSWERED_FROM_RECORDS)
    void aBareCodeIsJudgedByTheValueSetsOwnSystems() {
        Shelf shelf = new Shelf(FaceRootPackages.definitionsFor("r4",
                Set.of("StructureDefinition", "ValueSet")));
        Terms held = (system, code) -> GENDER.equals(system)
                ? Optional.of(new Terms.Membership(
                        Set.of("male", "female", "other", "unknown").contains(code), code))
                : Optional.empty();
        ElementPayloads payloads = new ElementVersion("r4").payloadsFor(held, shelf);

        List<String> unicorn = payloads.validate("Patient", payloads.read("Patient",
                "{\"resourceType\":\"Patient\",\"gender\":\"unicorn\"}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(unicorn.isEmpty(), "a gender outside the binding was accepted");
        assertTrue(unicorn.stream().anyMatch(issue -> issue.contains("unicorn")),
                "the refusal does not name the code: " + unicorn);

        List<String> female = payloads.validate("Patient", payloads.read("Patient",
                "{\"resourceType\":\"Patient\",\"gender\":\"female\"}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(female.isEmpty(), "a gender inside the binding was refused: " + female);

        // a binding whose system the tenant does not hold: nobody can say, so nobody refuses
        List<String> marital = payloads.validate("Patient", payloads.read("Patient",
                ("{\"resourceType\":\"Patient\",\"maritalStatus\":{\"coding\":[{\"system\":"
                        + "\"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus\",\"code\":\"Z\"}]}}")
                        .getBytes(StandardCharsets.UTF_8)));
        assertTrue(marital.isEmpty(), "a code from a system nobody here holds was refused: " + marital);
    }

    /** A tenant's records of its version: found by canonical, listed without their bodies. */
    static final class Shelf implements ObjectStore {
        private final Map<String, StoredObject> byUrl = new HashMap<>();
        private final Map<String, Held> inventory = new LinkedHashMap<>();

        Shelf(List<FaceRootPackages.Definition> definitions) {
            int i = 0;
            for (FaceRootPackages.Definition d : definitions) {
                String id = "rec-" + (++i);
                byUrl.put(d.typeName() + "|" + d.url(), new StoredObject(
                        id, d.typeName(), 1L, Instant.now(), d.document(), false, "4.0.1", null, null, List.of()));
                String json = new String(d.document(), StandardCharsets.UTF_8);
                Map<String, EnvelopeValue> firsts = new HashMap<>();
                String version = field(json, "version");
                if (version != null) {
                    firsts.put("version", EnvelopeValue.token(null, version));
                }
                String derivation = field(json, "derivation");
                if (derivation != null) {
                    firsts.put("derivation", EnvelopeValue.token(null, derivation));
                }
                inventory.put(d.typeName() + "|" + id, new Held(id, 1L,
                        List.of(new Identifier(Identifier.CANONICAL_SYSTEM, d.url())), firsts));
            }
        }

        private static String field(String json, String name) {
            Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        }

        @Override
        public List<Held> inventory(String typeName, List<String> paths) {
            return inventory.entrySet().stream().filter(e -> e.getKey().startsWith(typeName + "|"))
                    .map(Map.Entry::getValue).toList();
        }

        @Override
        public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
            return identifiers.stream().map(i -> byUrl.get(typeName + "|" + i.value()))
                    .filter(java.util.Objects::nonNull).toList();
        }

        @Override public PutResult put(PutRequest r) { return new PutResult("id", 1L, true); }
        @Override public PutResult put(PutRequest r, Handling.Authority c) { return put(r); }
        @Override public PutResult putIfAbsent(IdentityRef i, PutRequest r) { return put(r); }
        @Override public PutResult putConditional(IdentityRef i, PutRequest r) { return put(r); }
        @Override public Optional<StoredObject> get(String t, String id) { return Optional.empty(); }
        @Override public void delete(String t, String id, Long v) { }
        @Override public void delete(String t, String id, Long v, Handling.Authority c) { }
        @Override public List<StoredObject> history(String t, String id) { return List.of(); }
        @Override public List<StoredObject> select(Criteria c) { return List.of(); }
        @Override public long count(Criteria c) { return 0; }
        @Override public FeedChunk<StoredObject> page(Criteria c, String cursor) { return new FeedChunk<>(List.of(), null, true); }
        @Override public int rebuildEnvelopes(String t) { return 0; }
        @Override public cloud.jengu.dbo.core.api.TypeRegistration registrationOf(String t) { throw new UnsupportedOperationException("a shelf"); }
        @Override public int reindexUnder(cloud.jengu.dbo.core.api.TypeRegistration r) { throw new UnsupportedOperationException("a shelf"); }
    }
}
