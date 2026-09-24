package cloud.jengu.dbo.fhir.element;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Something reads the index.
 *
 * <p>Two measurements said the flat form is small — {@link WhatAClosureReachesTest}
 * that a tenant reaches 7.2% of a version, {@link WhatAFlatIndexCostsTest} that
 * the form costs a thirty-first of the model. Neither said anything could
 * CHECK a document against it, and a cheap store of definitions nothing can
 * read is not a step towards anything.
 *
 * <p>Cardinality first, because it is the check the database already answers
 * ({@code dbo.cardinality}), so a third answerer joins a comparison that
 * exists rather than starting one.
 *
 * <p><b>Top-level elements only, and said out loud.</b> A nested element's
 * maximum is per instance of its parent — {@code Patient.contact.name} may
 * appear once in EACH contact — and counting them across the document would
 * report a document that is correct. Depth is the next thing this needs and
 * it is not here, so the checker refuses to answer below depth one rather
 * than answering wrongly.
 */
final class AThirdAnswererChecksCardinalityTest {

    /**
     * The corpus: every conformance resource the release carries.
     *
     * <p>Not the specification's examples — the core package ships none, they
     * are a package of their own that this store does not carry. These are
     * better anyway: thousands of documents, authored by the people who wrote
     * the definitions they conform to, and four of the seven types are ones
     * the sample world's tenants actually declare.
     */
    private static final Set<String> CARRIED = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "ConceptMap", "OperationDefinition", "CompartmentDefinition"));

    /** One thing a checker found wrong, as a path and what it expected. */
    record Finding(String path, String detail) {
        @Override
        public String toString() {
            return path + ": " + detail;
        }
    }

    @Test
    void theSpecificationsOwnResourcesAreNotFaulted() throws IOException {
        FlatDefinitionIndex index = indexOverR5();
        List<String> falsePositives = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, byte[]> example : carriedResourcesOfR5().entrySet()) {
            String type = example.getKey().substring(0, example.getKey().indexOf('|'));
            List<Finding> found = check(index, FlatDefinitionIndex.PREFIX + type,
                    example.getValue());
            checked++;
            for (Finding one : found) {
                falsePositives.add(example.getKey() + " -> " + one);
            }
        }
        System.out.printf("%n=== %d conformance resources the release carries ===%n", checked);
        System.out.printf("descended into %d nodes, deepest path %d segments%n",
                descents, deepest);
        System.out.printf("faulted: %d%n", falsePositives.size());
        falsePositives.stream().limit(10).forEach(f -> System.out.println("  " + f));

        assertTrue(checked > 1000, "too few documents to mean anything: " + checked);
        // The walk has to have gone somewhere. Without this, a checker that
        // never descended would report the same clean corpus.
        assertTrue(descents > 100_000,
                "the walk barely descended, so a clean corpus means nothing: " + descents);
        assertTrue(deepest >= 4, "the walk never went deep: " + deepest);
        // A conformant document faulted is a checker that would refuse real
        // writes. There is no tolerance to set here: one is a defect.
        assertEquals(List.of(), falsePositives,
                "the checker faulted documents the specification ships as correct");
    }

    @Test
    void whatIsActuallyWrongIsFound() throws IOException {
        FlatDefinitionIndex index = indexOverR5();

        // Observation.status is 1..1 and Observation.code is 1..1.
        List<Finding> missing = check(index, FlatDefinitionIndex.PREFIX + "Observation",
                """
                {"resourceType":"Observation"}""".getBytes(StandardCharsets.UTF_8));
        assertTrue(missing.stream().anyMatch(f -> f.path().equals("Observation.status")),
                "a required element absent was not reported: " + missing);
        assertTrue(missing.stream().anyMatch(f -> f.path().equals("Observation.code")),
                "a required element absent was not reported: " + missing);

        // Patient.gender is 0..1, so two of them is one too many.
        List<Finding> tooMany = check(index, FlatDefinitionIndex.PREFIX + "Patient",
                """
                {"resourceType":"Patient","gender":["female","male"]}"""
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(tooMany.stream().anyMatch(f -> f.path().equals("Patient.gender")),
                "a maximum exceeded was not reported: " + tooMany);

        // Patient.name is 0..*, so many is correct and must not be reported.
        List<Finding> repeats = check(index, FlatDefinitionIndex.PREFIX + "Patient",
                """
                {"resourceType":"Patient","name":[{"family":"a"},{"family":"b"}]}"""
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), repeats, "an unbounded element was faulted for repeating");

        // DEPTH. Patient.contact is a backbone and Patient.contact.name is
        // 0..1, so two names in one contact is one too many — and two
        // contacts with one name each is correct. A checker counting across
        // the document rather than per parent gets the second one wrong,
        // which is exactly why depth one refused to answer.
        List<Finding> nested = check(index, FlatDefinitionIndex.PREFIX + "Patient",
                """
                {"resourceType":"Patient","contact":[{"name":[{"family":"a"},\
                {"family":"b"}]}]}""".getBytes(StandardCharsets.UTF_8));
        assertTrue(nested.stream().anyMatch(f -> f.path().endsWith("contact.name")),
                "a maximum exceeded inside a backbone was not reported: " + nested);

        List<Finding> perParent = check(index, FlatDefinitionIndex.PREFIX + "Patient",
                """
                {"resourceType":"Patient","contact":[{"name":{"family":"a"}},\
                {"name":{"family":"b"}}]}""".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), perParent,
                "a nested maximum was counted across the document instead of per parent: "
                        + perParent);

        // And into a DATATYPE's own structure: Patient.name is a HumanName,
        // whose elements are defined in HumanName rather than in Patient.
        List<Finding> intoType = check(index, FlatDefinitionIndex.PREFIX + "Patient",
                """
                {"resourceType":"Patient","name":[{"family":["a","b"]}]}"""
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(intoType.stream().anyMatch(f -> f.path().endsWith("name.family")),
                "the walk did not enter the datatype's own structure: " + intoType);

        // A choice: Observation.value[x] arrives as valueQuantity and is 0..1.
        List<Finding> choice = check(index, FlatDefinitionIndex.PREFIX + "Observation",
                """
                {"resourceType":"Observation","status":"final","code":{"text":"x"},\
                "valueQuantity":{"value":1}}""".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), choice, "a choice element was not recognised: " + choice);
    }

    // --- the checker ---

    /**
     * What the index says is wrong with this document, at every depth.
     *
     * <p>Driven from the DEFINITION rather than from the document, because a
     * required element that is absent is invisible in the document and is
     * most of what cardinality catches.
     *
     * <p>The walk changes structure where the document does. A backbone —
     * {@code Patient.contact} — is defined inside its own resource, so the
     * walk stays in the same structure and goes a path deeper. An element
     * typed as a datatype — {@code Patient.name} is a {@code HumanName} —
     * is defined in that type's OWN structure, so the walk moves there and
     * starts again at its root. That is the difference that makes a nested
     * maximum per parent instance rather than per document.
     */
    static List<Finding> check(FlatDefinitionIndex index, String structureUrl,
            byte[] document) throws IOException {
        Object tree = tree(document);
        List<Finding> findings = new ArrayList<>();
        if (tree instanceof Map<?, ?> root) {
            String type = structureUrl.substring(structureUrl.lastIndexOf('/') + 1);
            walk(index, structureUrl, type, root, type, findings);
        }
        return findings;
    }

    /**
     * Nodes the walk has descended into, and how deep it got.
     *
     * <p>Counted because "no document was faulted" is what a checker that
     * does nothing also reports. A walk that stopped at the root would pass
     * the corpus test in silence, and this is what makes that impossible.
     */
    private static int descents;
    private static int deepest;

    private static void walk(FlatDefinitionIndex index, String structureUrl, String path,
            Map<?, ?> node, String reported, List<Finding> findings) {
        for (int element : index.childrenOf(structureUrl, path)) {
            String full = index.pathOf(element);
            String name = full.substring(full.lastIndexOf('.') + 1);
            List<Object> present = new ArrayList<>();
            String chosen = null;
            if (name.endsWith("[x]")) {
                // A choice arrives under a name the definition never states:
                // value[x] is valueQuantity on the wire, and the suffix is
                // the type, which is also how the walk knows where to go next.
                String stem = name.substring(0, name.length() - 3);
                for (Map.Entry<?, ?> entry : node.entrySet()) {
                    String field = String.valueOf(entry.getKey());
                    if (field.startsWith(stem) && field.length() > stem.length()
                            && Character.isUpperCase(field.charAt(stem.length()))) {
                        present.addAll(values(entry.getValue()));
                        chosen = field.substring(stem.length());
                    }
                }
            } else {
                present.addAll(values(node.get(name)));
            }

            short min = index.min[element];
            short max = index.max[element];
            if (present.size() < min) {
                findings.add(new Finding(reported + "." + name,
                        "minimum " + min + ", found " + present.size()));
            }
            if (max >= 0 && present.size() > max) {
                findings.add(new Finding(reported + "." + name,
                        "maximum " + max + ", found " + present.size()));
            }
            if (present.isEmpty()) {
                continue;
            }

            List<String> types = index.typesOf(element);
            String into = chosen != null ? chosen : types.size() == 1 ? types.get(0) : null;
            boolean backbone = types.contains("BackboneElement") || types.contains("Element");
            int at = 0;
            for (Object one : present) {
                String where = reported + "." + name
                        + (present.size() > 1 ? "[" + at++ + "]" : "");
                if (!(one instanceof Map<?, ?> object)) {
                    continue;
                }
                int depth = where.length() - where.replace(".", "").length();
                deepest = Math.max(deepest, depth);
                if (backbone) {
                    descents++;
                    walk(index, structureUrl, full, object, where, findings);
                } else if (into != null && !SKIPPED.contains(into)) {
                    String url = FlatDefinitionIndex.PREFIX + into;
                    if (index.holds(url)) {
                        descents++;
                        walk(index, url, into, object, where, findings);
                    }
                }
            }
        }
    }

    /**
     * Types the walk does not follow.
     *
     * <p>A contained resource may be of any type, and the element says only
     * {@code Resource} — walking it would check a Patient against Resource's
     * definition, find its own elements undeclared, and be right about
     * nothing. What a document may contain is a question about the tenant's
     * declaration, not about cardinality.
     */
    private static final Set<String> SKIPPED = Set.of("Resource", "DomainResource");

    /** One value, or the entries of a repeat. */
    private static List<Object> values(Object held) {
        if (held == null) {
            return List.of();
        }
        if (held instanceof List<?> many) {
            return new ArrayList<>(many);
        }
        return List.of(held);
    }

    /**
     * The document as maps and lists.
     *
     * <p>A tree, where the index deliberately is not one: a document is one
     * resource and a definition corpus is ninety thousand elements, so the
     * form that is wrong for the second is the obvious one for the first.
     */
    private static Object tree(byte[] document) throws IOException {
        try (JsonParser p = new JsonFactory().createParser(document)) {
            p.nextToken();
            return value(p);
        }
    }

    private static Object value(JsonParser p) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_OBJECT) {
            Map<String, Object> object = new LinkedHashMap<>();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                object.put(field, value(p));
            }
            return object;
        }
        if (t == JsonToken.START_ARRAY) {
            List<Object> many = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                many.add(value(p));
            }
            return many;
        }
        return p.getValueAsString();
    }

    // --- what it reads, and what it reads about ---

    static FlatDefinitionIndex indexOverR5() throws IOException {
        List<FaceRootPackages.Definition> carried =
                FaceRootPackages.definitionsFor("r5", Set.of("StructureDefinition"));
        List<byte[]> documents = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (FaceRootPackages.Definition one : carried) {
            if (one.url() != null) {
                documents.add(one.document());
                urls.add(one.url());
            }
        }
        return FlatDefinitionIndex.over(documents, urls);
    }

    /** Every conformance resource the release carries, keyed type|url. */
    static Map<String, byte[]> carriedResourcesOfR5() throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (CarriedDefinitions.Carried carried : CarriedDefinitions.forVersion("r5")) {
            for (var indexed : CarriedDefinitions.indexed(carried,
                    CARRIED.toArray(new String[0]))) {
                try (var in = CarriedDefinitions.read(indexed)) {
                    out.put(indexed.getResourceType() + "|" + indexed.getUrl(),
                            in.readAllBytes());
                }
            }
        }
        return out;
    }
}
