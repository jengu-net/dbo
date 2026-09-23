package cloud.jengu.dbo.fhir.element;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A version's definitions as flat arrays over one interned dictionary.
 *
 * <p>The form item 025 proposes, built far enough to be measured and read
 * from. It is a SPIKE and lives in a test source set on purpose: whether the
 * `fhir/index` module is worth creating is what the measurements decide, and
 * a module created first would be the answer assumed rather than found.
 *
 * <p>What it holds is what {@code definitions.definition_element} stores and
 * what item 025 says a checker reads — path, parent, min, max, type codes,
 * binding and strength, invariants. No toolchain: it is built by a token scan
 * over the carried packages, which is the claim the design rests on.
 *
 * <p>A path is stored once and referred to by number wherever it repeats.
 * That is the whole difference from the model form, where the same text is a
 * {@code String} in a {@code StringType} in an {@code ElementDefinition} for
 * every occurrence.
 */
final class FlatDefinitionIndex {

    static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    String[] words;
    /** Per structure, in the order they were read. */
    String[] structureUrl;
    /** Per element. */
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
    /** Where each structure's elements begin, so one can be read without a scan. */
    private final Map<String, Integer> firstElementOf = new LinkedHashMap<>();

    /**
     * The direct children of a path, by structure — {@code Patient.contact}
     * answers {@code Patient.contact.name} and the rest, and nothing deeper.
     *
     * <p>Built once, because a checker asks this at every node of every
     * document and scanning the structure's elements each time would make
     * the walk quadratic in the size of a definition.
     */
    List<Integer> childrenOf(String structureUrl, String path) {
        if (childrenByPath == null) {
            Map<String, List<Integer>> built = new HashMap<>();
            for (int i = 0; i < elements; i++) {
                String full = pathOf(i);
                int cut = full == null ? -1 : full.lastIndexOf('.');
                if (cut < 0) {
                    continue;
                }
                built.computeIfAbsent(
                        this.structureUrl[structureOf[i]] + "|" + full.substring(0, cut),
                        k -> new ArrayList<>()).add(i);
            }
            childrenByPath = built;
        }
        return childrenByPath.getOrDefault(structureUrl + "|" + path, List.of());
    }

    private Map<String, List<Integer>> childrenByPath;

    /** Whether this structure is held at all. */
    boolean holds(String url) {
        return firstElementOf.containsKey(url);
    }

    /** The elements of one structure, in document order; empty if it is not held. */
    List<Integer> elementsOf(String url) {
        Integer from = firstElementOf.get(url);
        List<Integer> out = new ArrayList<>();
        if (from == null) {
            return out;
        }
        int structure = structureOf[from];
        for (int i = from; i < elements && structureOf[i] == structure; i++) {
            out.add(i);
        }
        return out;
    }

    String pathOf(int element) {
        return words[pathId[element]];
    }

    /** The type codes this element may take. */
    List<String> typesOf(int element) {
        List<String> out = new ArrayList<>();
        for (int i = typeAt[element]; i < typeAt[element + 1]; i++) {
            out.add(words[typeCodes[i]]);
        }
        return out;
    }

    static FlatDefinitionIndex over(List<byte[]> documents, List<String> urls) throws IOException {
        Map<String, Integer> dictionary = new HashMap<>();
        List<String> words = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        List<Integer> constraints = new ArrayList<>();
        int[] fixedOrPattern = new int[1];
        FlatDefinitionIndex index = new FlatDefinitionIndex();
        index.structureUrl = urls.toArray(new String[0]);
        for (int structure = 0; structure < documents.size(); structure++) {
            int first = rows.size();
            rows.addAll(rowsOf(documents.get(structure), structure, dictionary, words,
                    types, constraints, fixedOrPattern));
            if (rows.size() > first) {
                index.firstElementOf.put(urls.get(structure), first);
            }
        }

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
        index.constraintAt[rows.size()] = constraints.size();
        index.typeCodes = new int[types.size()];
        for (int i = 0; i < types.size(); i++) {
            index.typeCodes[i] = types.get(i);
        }
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

    private record Frame(String field, boolean array) {
    }

    private static List<Row> rowsOf(byte[] document, int structure,
            Map<String, Integer> dictionary, List<String> words, List<Integer> types,
            List<Integer> constraints, int[] fixedOrPattern) throws IOException {
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
                            && enclosing.array() && "element".equals(enclosing.field())
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
                    if (closed != null && "snapshot".equals(closed.field())
                            && frames.size() == snapshotDepth) {
                        snapshotDepth = -1;
                    }
                    if (closed != null && !closed.array() && row != null && path != null
                            && frames.peek() != null && frames.peek().array()
                            && "element".equals(frames.peek().field())) {
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
                String named = in != null && in.array() ? in.field() : field;
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

    private static String frameField(Deque<Frame> frames, int n) {
        int i = 0;
        for (Frame one : frames) {
            if (i++ == n) {
                return one.field();
            }
        }
        return null;
    }
}
