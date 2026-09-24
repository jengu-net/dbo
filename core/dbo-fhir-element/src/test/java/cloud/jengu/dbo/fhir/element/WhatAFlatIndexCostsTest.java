package cloud.jengu.dbo.fhir.element;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The step from an element count to a megabyte.
 *
 * <p>{@link WhatAClosureReachesTest} counted what a tenant's declared types
 * reach: 1,164 of r5's 16,150 elements. That is a count, and item 025's claim
 * is a quantity. This holds the SAME content in the two forms and asks the JVM
 * which is smaller — the closure parsed into the model the toolchain
 * navigates, against the closure as flat arrays over an interned dictionary.
 *
 * <p><b>The comparison is honest because the content is identical.</b> Both
 * sides hold the same 78 structures. Neither builds a worker context: a
 * context is a face's whole corpus and would be measuring something else, and
 * the point here is the FORM, which is what item 025 says the cost is.
 *
 * <p><b>What the flat side holds</b> is what item 025 says a checker reads and
 * what {@code definitions.definition_element} already stores: path, parent,
 * min, max, the type codes, the binding and its strength. Dropping a column to
 * flatter the measurement would make it meaningless, so the columns are the
 * ones the database's own checks were written against.
 */
final class WhatAFlatIndexCostsTest {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    private static final Set<String> HOGWARTS = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "Organization", "Person", "Practitioner", "PractitionerRole",
            "Patient", "Encounter", "Observation"));

    @Test
    void compare() throws Exception {
        List<FaceRootPackages.Definition> carried =
                FaceRootPackages.definitionsFor("r5", Set.of("StructureDefinition"));
        Map<String, byte[]> byUrl = new LinkedHashMap<>();
        Map<String, Set<String>> composes = new LinkedHashMap<>();
        Map<String, String> bases = new LinkedHashMap<>();
        for (FaceRootPackages.Definition one : carried) {
            if (one.url() == null) {
                continue;
            }
            byUrl.put(one.url(), withoutDifferential(one.document()));
            Edges e = edges(one.document());
            composes.put(one.url(), e.composes());
            if (e.base() != null) {
                bases.put(one.url(), e.base());
            }
        }

        Set<String> closure = walk(byUrl.keySet(), composes, bases, HOGWARTS);
        List<byte[]> documents = new ArrayList<>();
        long documentBytes = 0;
        for (String url : closure) {
            byte[] d = byUrl.get(url);
            documents.add(d);
            documentBytes += d.length;
        }
        byUrl = null;
        composes = null;
        bases = null;
        carried = null;

        // --- the flat form ---
        long before = heapInUse();
        FlatIndex flat = FlatIndex.over(documents);
        long flatCost = heapInUse() - before;
        int elements = flat.elements;
        int words = flat.words.length;
        int constraintCount = flat.constraints.length / 3;
        int fixedOrPattern = flat.fixedOrPattern;
        // held across the measurement, or the collector takes it first
        assert flat.pathId.length > 0;

        // --- the model form, the same structures ---
        flat = null;
        before = heapInUse();
        List<org.hl7.fhir.r5.model.StructureDefinition> parsed = new ArrayList<>();
        for (byte[] d : documents) {
            parsed.add((org.hl7.fhir.r5.model.StructureDefinition)
                    new org.hl7.fhir.r5.formats.JsonParser().parse(d));
        }
        long modelCost = heapInUse() - before;
        int modelElements = 0;
        for (org.hl7.fhir.r5.model.StructureDefinition sd : parsed) {
            modelElements += sd.hasSnapshot() ? sd.getSnapshot().getElement().size() : 0;
        }

        System.out.println();
        System.out.println("=== the same closure, two forms ===");
        System.out.printf("structures                  %6d%n", documents.size());
        System.out.printf("documents on the wire       %6d KB%n", documentBytes / 1024);
        System.out.println();
        System.out.printf("flat index                  %6d KB   %d elements, %d interned words%n",
                flatCost / 1024, elements, words);
        System.out.printf("  constraints held          %6d   fixed/pattern left out: %d%n",
                constraintCount, fixedOrPattern);
        System.out.printf("  per element               %6d bytes%n", flatCost / Math.max(1, elements));
        System.out.printf("model objects               %6d KB   %d elements%n",
                modelCost / 1024, modelElements);
        System.out.printf("  per element               %6d bytes%n",
                modelCost / Math.max(1, modelElements));
        System.out.println();
        System.out.printf("ratio                       %6.1fx%n",
                (double) modelCost / Math.max(1, flatCost));
        long perElementFlat = flatCost / Math.max(1, elements);
        long perElementModel = modelCost / Math.max(1, modelElements);
        System.out.println();
        System.out.println("=== carried out to a whole version ===");
        System.out.printf("r5's 16,150 snapshot elements, flat   %6d KB%n",
                16150L * perElementFlat / 1024);
        System.out.printf("r5's 16,150 snapshot elements, model  %6d MB%n",
                16150L * perElementModel / (1024 * 1024));
        System.out.println("a face measures 225 MB, which is the line above plus the");
        System.out.println("differentials, the terminology resources, the tools package");
        System.out.println("and the context's own indexes — so the two reconcile.");
        System.out.println();

        org.junit.jupiter.api.Assertions.assertEquals(elements, modelElements,
                "the two forms must hold the same elements or the comparison says nothing");
        // Measured at 31x. Asserted at 10, because the claim being defended is
        // "an order of magnitude", and a ratchet on 31 would fail on a package
        // upgrade rather than on a regression.
        org.junit.jupiter.api.Assertions.assertTrue(flatCost * 10 < modelCost,
                "the flat form is no longer an order of magnitude smaller: flat " + flatCost
                        + " against model " + modelCost + " — which is item 025's whole premise");

        // The comparison is only fair while the flat side holds what a checker
        // reads. If base definitions start carrying fixed or pattern values,
        // the flat form has to hold them and this measurement is stale.
        org.junit.jupiter.api.Assertions.assertEquals(0, fixedOrPattern,
                "base definitions now carry fixed/pattern values, which the flat "
                        + "index does not hold — the ratio above is measuring an omission");
    }

    /**
     * The closure as flat arrays over one dictionary.
     *
     * <p>A path is stored once and referred to by number wherever it repeats,
     * which is the whole difference: the model holds a {@code String} in a
     * {@code StringType} in an {@code ElementDefinition} for every occurrence.
     */
    private static final class FlatIndex {
        String[] words;
        int[] structureOf;
        int[] pathId;
        int[] parent;
        short[] min;
        short[] max;
        int[] typeAt;
        int[] typeCodes;
        byte[] bindingStrength;
        int[] bindingValueSet;
        int[] constraintAt;
        int[] constraints;
        int elements;
        int fixedOrPattern;

        static FlatIndex over(List<byte[]> documents) throws IOException {
            Map<String, Integer> dictionary = new HashMap<>();
            List<String> words = new ArrayList<>();
            List<Row> rows = new ArrayList<>();
            List<Integer> types = new ArrayList<>();
            List<Integer> constraints = new ArrayList<>();
            int[] fixedOrPattern = new int[1];
            int structure = 0;
            for (byte[] document : documents) {
                for (Row row : rowsOf(document, structure, dictionary, words, types,
                        constraints, fixedOrPattern)) {
                    rows.add(row);
                }
                structure++;
            }

            FlatIndex index = new FlatIndex();
            index.fixedOrPattern = fixedOrPattern[0];
            index.elements = rows.size();
            index.words = words.toArray(new String[0]);
            index.structureOf = new int[rows.size()];
            index.pathId = new int[rows.size()];
            index.parent = new int[rows.size()];
            index.min = new short[rows.size()];
            index.max = new short[rows.size()];
            index.typeAt = new int[rows.size() + 1];
            index.constraintAt = new int[rows.size() + 1];
            index.bindingStrength = new byte[rows.size()];
            index.bindingValueSet = new int[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                index.structureOf[i] = r.structure;
                index.pathId[i] = r.path;
                index.parent[i] = r.parent;
                index.min[i] = r.min;
                index.max[i] = r.max;
                index.typeAt[i] = r.typeAt;
                index.constraintAt[i] = r.constraintAt;
                index.bindingStrength[i] = r.bindingStrength;
                index.bindingValueSet[i] = r.bindingValueSet;
            }
            index.typeAt[rows.size()] = types.size();
            index.typeCodes = new int[types.size()];
            for (int i = 0; i < types.size(); i++) {
                index.typeCodes[i] = types.get(i);
            }
            // Three interned ids per constraint: key, severity, expression.
            // Interned because ele-1 is inherited onto nearly every element
            // there is, so one expression is stored once and pointed at 1,164
            // times — which is the same saving a path gets and the reason the
            // form is a dictionary rather than a row of strings.
            index.constraintAt[rows.size()] = constraints.size();
            index.constraints = new int[constraints.size()];
            for (int i = 0; i < constraints.size(); i++) {
                index.constraints[i] = constraints.get(i);
            }
            return index;
        }

        private static final class Row {
            int structure;
            int path;
            int parent = -1;
            short min;
            short max;
            int typeAt;
            int constraintAt;
            byte bindingStrength;
            int bindingValueSet = -1;
        }

        /** One structure's snapshot, read straight off the bytes. */
        private static List<Row> rowsOf(byte[] document, int structure,
                Map<String, Integer> dictionary, List<String> words,
                List<Integer> types, List<Integer> constraints,
                int[] fixedOrPattern) throws IOException {
            List<Row> rows = new ArrayList<>();
            Map<String, Integer> byPath = new HashMap<>();
            try (JsonParser p = new JsonFactory().createParser(document)) {
                Deque<Frame> frames = new ArrayDeque<>();
                String field = null;
                int snapshotDepth = -1;
                Row row = null;
                String path = null;
                while (p.nextToken() != null) {
                    JsonToken t = p.currentToken();
                    if (t == JsonToken.FIELD_NAME) {
                        field = p.currentName();
                        continue;
                    }
                    if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                        Frame enclosing = frames.peek();
                        if (t == JsonToken.START_OBJECT && enclosing != null
                                && enclosing.array && "element".equals(enclosing.field)
                                && snapshotDepth >= 0) {
                            row = new Row();
                            row.structure = structure;
                            row.typeAt = types.size();
                            row.constraintAt = constraints.size();
                            path = null;
                        }
                        if ("snapshot".equals(field) && snapshotDepth < 0) {
                            snapshotDepth = frames.size();
                        }
                        frames.push(new Frame(field, t == JsonToken.START_ARRAY));
                        field = null;
                        continue;
                    }
                    if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) {
                        Frame closed = frames.poll();
                        if (closed != null && "snapshot".equals(closed.field)
                                && frames.size() == snapshotDepth) {
                            snapshotDepth = -1;
                        }
                        if (closed != null && !closed.array && row != null && path != null
                                && frames.peek() != null && frames.peek().array
                                && "element".equals(frames.peek().field)) {
                            byPath.put(path, rows.size());
                            int cut = path.lastIndexOf('.');
                            if (cut > 0) {
                                row.parent = byPath.getOrDefault(path.substring(0, cut), -1);
                            }
                            rows.add(row);
                            row = null;
                            path = null;
                        }
                        field = null;
                        continue;
                    }
                    Frame in = frames.peek();
                    String named = in != null && in.array ? in.field : field;
                    String value = p.getValueAsString();
                    field = null;
                    if (named == null || value == null || row == null || snapshotDepth < 0) {
                        continue;
                    }
                    if ("path".equals(named) && "element".equals(frameField(frames, 1))) {
                        path = value;
                        row.path = intern(value, dictionary, words);
                    } else if ("min".equals(named) && "element".equals(frameField(frames, 1))) {
                        row.min = (short) Math.min(Short.MAX_VALUE, p.getValueAsLong());
                    } else if ("max".equals(named) && "element".equals(frameField(frames, 1))) {
                        row.max = "*".equals(value) ? -1
                                : (short) Math.min(Short.MAX_VALUE, safeInt(value));
                    } else if ("code".equals(named) && "type".equals(frameField(frames, 1))) {
                        types.add(intern(value, dictionary, words));
                    } else if ("strength".equals(named) && "binding".equals(frameField(frames, 0))) {
                        row.bindingStrength = (byte) switch (value) {
                            case "required" -> 1;
                            case "extensible" -> 2;
                            case "preferred" -> 3;
                            default -> 4;
                        };
                    } else if ("valueSet".equals(named) && "binding".equals(frameField(frames, 0))) {
                        row.bindingValueSet = intern(value, dictionary, words);
                    } else if ("constraint".equals(frameField(frames, 1))
                            && ("key".equals(named) || "severity".equals(named)
                                || "expression".equals(named))) {
                        constraints.add(intern(value, dictionary, words));
                    } else if (named.startsWith("fixed") || named.startsWith("pattern")) {
                        // Counted rather than held: whether leaving them out
                        // matters is a question about how many there are, and
                        // a number answers it where an assurance does not.
                        fixedOrPattern[0]++;
                    }
                }
            }
            return rows;
        }

        private static int safeInt(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException notANumber) {
                return 1;
            }
        }

        private static int intern(String value, Map<String, Integer> dictionary, List<String> words) {
            Integer known = dictionary.get(value);
            if (known != null) {
                return known;
            }
            int id = words.size();
            words.add(value);
            dictionary.put(value, id);
            return id;
        }
    }

    // --- the closure walk, as WhatAClosureReachesTest computes it ---

    private record Edges(String base, Set<String> composes) {
    }

    private record Frame(String field, boolean array) {
    }

    private static Set<String> walk(Set<String> corpus, Map<String, Set<String>> composes,
            Map<String, String> bases, Set<String> seedTypes) {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String type : seedTypes) {
            String url = PREFIX + type;
            if (corpus.contains(url) && reached.add(url)) {
                pending.add(url);
            }
        }
        while (!pending.isEmpty()) {
            String url = pending.poll();
            for (String edge : composes.getOrDefault(url, Set.of())) {
                if (corpus.contains(edge) && reached.add(edge)) {
                    pending.add(edge);
                }
            }
            String base = bases.get(url);
            if (base != null && corpus.contains(base) && reached.add(base)) {
                pending.add(base);
            }
        }
        return reached;
    }

    private static Edges edges(byte[] document) throws IOException {
        Set<String> composes = new LinkedHashSet<>();
        String base = null;
        try (JsonParser p = new JsonFactory().createParser(document)) {
            Deque<Frame> frames = new ArrayDeque<>();
            String field = null;
            int snapshotDepth = -1;
            while (p.nextToken() != null) {
                JsonToken t = p.currentToken();
                if (t == JsonToken.FIELD_NAME) {
                    field = p.currentName();
                    continue;
                }
                if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                    if ("snapshot".equals(field) && snapshotDepth < 0) {
                        snapshotDepth = frames.size();
                    }
                    frames.push(new Frame(field, t == JsonToken.START_ARRAY));
                    field = null;
                    continue;
                }
                if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) {
                    Frame closed = frames.poll();
                    if (closed != null && "snapshot".equals(closed.field())
                            && frames.size() == snapshotDepth) {
                        snapshotDepth = -1;
                    }
                    field = null;
                    continue;
                }
                Frame in = frames.peek();
                String named = in != null && in.array() ? in.field() : field;
                String value = p.getValueAsString();
                field = null;
                if (named == null || value == null) {
                    continue;
                }
                if (frames.size() == 1 && "baseDefinition".equals(named)) {
                    base = value;
                } else if (snapshotDepth >= 0 && "code".equals(named)
                        && "type".equals(frameField(frames, 1))) {
                    composes.add(value.startsWith("http") ? value : PREFIX + value);
                }
            }
        }
        return new Edges(base, composes);
    }

    private static String frameField(Deque<Frame> frames, int n) {
        int i = 0;
        for (Frame one : frames) {
            if (i++ == n) {
                return one.field();
            }
        }
        return null;
    }

    /**
     * What every carried version costs in the flat form, together.
     *
     * <p>The per-element rate above was carried out to a whole version by
     * multiplication, which assumes the rate holds at scale — and it does not,
     * because the dictionary is shared: the more elements, the more paths and
     * type codes are ones it has already interned. So the figure is measured
     * rather than multiplied.
     *
     * <p>Three versions rather than one, because that is item 024's criterion.
     * Its recorded baseline is 226 MB for a face's first tenant and 445 for a
     * second face on top, and the thing it says must stop being true is that
     * another face costs another few hundred megabytes.
     */
    @Test
    void whatEveryCarriedVersionCostsAtOnce() throws Exception {
        java.util.List<String> versions = new ArrayList<>(CarriedDefinitions.versions());
        java.util.Collections.sort(versions);

        Map<String, List<byte[]>> byVersion = new LinkedHashMap<>();
        for (String version : versions) {
            List<byte[]> documents = new ArrayList<>();
            for (FaceRootPackages.Definition one
                    : FaceRootPackages.definitionsFor(version, Set.of("StructureDefinition"))) {
                if (one.url() != null) {
                    documents.add(withoutDifferential(one.document()));
                }
            }
            byVersion.put(version, documents);
        }

        System.out.println();
        System.out.println("=== every carried version, in the flat form ===");
        long together = 0;
        int elementsTogether = 0;
        List<FlatIndex> held = new ArrayList<>();
        long before = heapInUse();
        for (Map.Entry<String, List<byte[]>> one : byVersion.entrySet()) {
            FlatIndex index = FlatIndex.over(one.getValue());
            held.add(index);
            elementsTogether += index.elements;
        }
        together = heapInUse() - before;
        for (int i = 0; i < versions.size(); i++) {
            System.out.printf("  %-4s %4d structures %6d elements%n",
                    versions.get(i), byVersion.get(versions.get(i)).size(),
                    held.get(i).elements);
        }
        System.out.printf("all three, held at once   %6d KB   %d elements, %d bytes each%n",
                together / 1024, elementsTogether,
                together / Math.max(1, elementsTogether));
        System.out.println();
        System.out.println("the recorded baseline for the same content in the model form:");
        System.out.println("  oneServedTenantBeforeAnyWrite  226 MB   (a face's first tenant)");
        System.out.println("  aSecondFaceServed              445 MB   (a second version on top)");
        System.out.println();

        // Held across the measurement, or the collector has the last word.
        assertTrue(held.stream().allMatch(i -> i.elements > 0), "an index came out empty");

        // Item 024's criterion, asked of the form rather than of a tenant:
        // every version this release carries, together, under one tenth of
        // what ONE of them costs as model objects today.
        assertTrue(together < 22L * 1024 * 1024,
                "every carried version in the flat form now costs " + (together / 1024 / 1024)
                        + " MB, which is no longer a tenth of the 226 MB one of them "
                        + "costs as model objects");
    }

    /**
     * The same document without its differential.
     *
     * <p>A structure carries both forms and the flat index holds only the
     * snapshot, so leaving the differential in would have the model side
     * holding content the flat side was never asked for — which would make
     * the ratio a measurement of what was left out rather than of the form.
     *
     * <p>The documentation on each element — its {@code short}, its
     * {@code definition}, its {@code comment} — is NOT stripped, and the model
     * side does carry it. That difference is real and is left in deliberately:
     * it is item 025's argument rather than a flaw in the comparison. A model
     * built for authoring holds the prose a human reads; a checker does not
     * read it, and refusing to hold it is most of what the flat form is.
     */
    private static byte[] withoutDifferential(byte[] document) throws IOException {
        org.hl7.fhir.utilities.json.model.JsonObject json =
                org.hl7.fhir.utilities.json.parser.JsonParser.parseObject(
                        new java.io.ByteArrayInputStream(document));
        json.remove("differential");
        return org.hl7.fhir.utilities.json.parser.JsonParser.compose(json)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long heapInUse() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
