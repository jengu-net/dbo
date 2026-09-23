package cloud.jengu.dbo.fhir.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A set of definitions as flat arrays over one interned dictionary.
 *
 * <p>What it holds is what {@code definitions.definition_element} stores and
 * what a checker reads: path, parent, min, max, type codes, binding and its
 * strength, what an element must equal or contain, and the invariants. It
 * does not hold {@code short},
 * {@code definition} or {@code comment} — the prose a model built for
 * authoring carries and a checker never reads — and declining to hold it is
 * most of what this form is.
 *
 * <p>A path, a type code and an invariant expression are stored once and
 * referred to by number wherever they repeat. That is the difference from the
 * model form, where the same text is a {@code String} in a {@code StringType}
 * in an {@code ElementDefinition} for every occurrence, and it is why one
 * element costs a hundred and some bytes here against five thousand there.
 *
 * <p>An index is built by a {@link Builder} and never afterwards: it is a
 * projection of rows that arrived, and a thing that could be edited in place
 * would be a second place a definition lives.
 */
public final class DefinitionIndex {

    /** No binding, which is most elements. */
    public static final byte NO_BINDING = 0;
    public static final byte REQUIRED = 1;
    public static final byte EXTENSIBLE = 2;
    public static final byte PREFERRED = 3;
    public static final byte EXAMPLE = 4;

    /** Unbounded, which is how {@code max: "*"} is held. */
    public static final int UNBOUNDED = -1;

    private final String[] words;
    private final String[] canonicals;
    private final int[] canonicalOf;
    private final int[] pathId;
    private final int[] parent;
    private final short[] min;
    private final short[] max;
    private final int[] typeAt;
    private final int[] typeCodes;
    private final byte[] bindingStrength;
    private final int[] bindingValueSet;
    private final int[] fixedValue;
    private final int[] patternValue;
    private final int[] invariantAt;
    private final int[] invariants;
    private final int elements;
    private final Map<String, Integer> firstElementOf;

    /**
     * The direct children of a path, by canonical — {@code Patient.contact}
     * answers {@code Patient.contact.name} and nothing deeper.
     *
     * <p>Built on first use rather than eagerly, because a process that only
     * reads one structure should not pay for the rest, and never twice: a
     * checker asks this at every node of every document, and scanning a
     * structure's elements each time would make the walk quadratic in the
     * size of a definition.
     */
    private Map<String, List<Integer>> childrenByPath;

    private DefinitionIndex(Builder from) {
        this.words = from.words.toArray(new String[0]);
        this.canonicals = from.canonicals.toArray(new String[0]);
        this.elements = from.rows.size();
        this.firstElementOf = Map.copyOf(from.firstElementOf);
        this.canonicalOf = new int[elements];
        this.pathId = new int[elements];
        this.parent = new int[elements];
        this.min = new short[elements];
        this.max = new short[elements];
        this.typeAt = new int[elements + 1];
        this.invariantAt = new int[elements + 1];
        this.bindingStrength = new byte[elements];
        this.bindingValueSet = new int[elements];
        this.fixedValue = new int[elements];
        this.patternValue = new int[elements];
        for (int i = 0; i < elements; i++) {
            Builder.Row r = from.rows.get(i);
            canonicalOf[i] = r.canonical;
            pathId[i] = r.path;
            parent[i] = r.parent;
            min[i] = r.min;
            max[i] = r.max;
            typeAt[i] = r.typeAt;
            invariantAt[i] = r.invariantAt;
            bindingStrength[i] = r.bindingStrength;
            bindingValueSet[i] = r.bindingValueSet;
            fixedValue[i] = r.fixedValue;
            patternValue[i] = r.patternValue;
        }
        this.typeAt[elements] = from.typeCodes.size();
        this.invariantAt[elements] = from.invariants.size();
        this.typeCodes = toArray(from.typeCodes);
        this.invariants = toArray(from.invariants);
    }

    private static int[] toArray(List<Integer> of) {
        int[] out = new int[of.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = of.get(i);
        }
        return out;
    }

    /** How many elements are held, over every structure. */
    public int elements() {
        return elements;
    }

    /** How many structures are held. */
    public int structures() {
        return firstElementOf.size();
    }

    /** How many distinct strings the dictionary holds. */
    public int words() {
        return words.length;
    }

    /** Whether this structure is held at all. */
    public boolean holds(String canonical) {
        return firstElementOf.containsKey(canonical);
    }

    /** Every structure held, in the order it arrived. */
    public List<String> held() {
        return List.copyOf(firstElementOf.keySet());
    }

    /** The elements of one structure in document order; empty where it is not held. */
    public List<Integer> elementsOf(String canonical) {
        Integer from = firstElementOf.get(canonical);
        List<Integer> out = new ArrayList<>();
        if (from == null) {
            return out;
        }
        int structure = canonicalOf[from];
        for (int i = from; i < elements && canonicalOf[i] == structure; i++) {
            out.add(i);
        }
        return out;
    }

    /**
     * The path this structure's own root element carries.
     *
     * <p>Asked rather than derived. A base definition's canonical ends in the
     * type it defines, so the last segment of the url is the root path and
     * looks like a fine shortcut; a profile's canonical ends in the profile's
     * name, and a walk that started at {@code IndeksIkPatsient} finds nothing
     * in a document whose paths all begin {@code Patient}. The rows say it,
     * so nothing has to guess.
     *
     * @return the root path, or null where the structure is not held
     */
    public String rootPathOf(String canonical) {
        Integer first = firstElementOf.get(canonical);
        return first == null ? null : pathOf(first);
    }

    /** The direct children of a path within one structure. */
    public List<Integer> childrenOf(String canonical, String path) {
        if (childrenByPath == null) {
            Map<String, List<Integer>> built = new HashMap<>();
            for (int i = 0; i < elements; i++) {
                String full = pathOf(i);
                int cut = full == null ? -1 : full.lastIndexOf('.');
                if (cut < 0) {
                    continue;
                }
                built.computeIfAbsent(key(canonicals[canonicalOf[i]], full.substring(0, cut)),
                        k -> new ArrayList<>()).add(i);
            }
            childrenByPath = built;
        }
        return childrenByPath.getOrDefault(key(canonical, path), List.of());
    }

    private static String key(String canonical, String path) {
        return canonical + "|" + path;
    }

    public String canonicalOf(int element) {
        return canonicals[canonicalOf[element]];
    }

    public String pathOf(int element) {
        return words[pathId[element]];
    }

    /** The element this one hangs under, or -1 at the root of a structure. */
    public int parentOf(int element) {
        return parent[element];
    }

    public int minOf(int element) {
        return min[element];
    }

    /** The maximum, or {@link #UNBOUNDED}. */
    public int maxOf(int element) {
        return max[element];
    }

    /** The type codes this element may take. */
    public List<String> typesOf(int element) {
        List<String> out = new ArrayList<>();
        for (int i = typeAt[element]; i < typeAt[element + 1]; i++) {
            out.add(words[typeCodes[i]]);
        }
        return out;
    }

    /** One of the strength constants; {@link #NO_BINDING} where there is none. */
    public byte bindingStrengthOf(int element) {
        return bindingStrength[element];
    }

    /** The value set the binding names, or null. */
    public String bindingValueSetOf(int element) {
        int word = bindingValueSet[element];
        return word < 0 ? null : words[word];
    }

    /**
     * What this element must EQUAL, as the JSON the profile stated, or null.
     *
     * <p>Held as text rather than as a parsed value, and interned like
     * everything else: the same fixed system is pinned on every element of
     * every profile a tenant issues under it, so the text is stored once and
     * pointed at. A checker compares it against the document's own text,
     * which is also what keeps a decimal's written precision out of a double.
     */
    public String fixedOf(int element) {
        int word = fixedValue[element];
        return word < 0 ? null : words[word];
    }

    /**
     * What this element must CONTAIN, as the JSON the profile stated, or null.
     *
     * <p>Containment rather than equality is what a pattern means: the
     * element carries what the pattern states and may carry more.
     */
    public String patternOf(int element) {
        int word = patternValue[element];
        return word < 0 ? null : words[word];
    }

    /** One invariant, as the rows carry it. */
    public record Invariant(String key, String severity, String expression) {
    }

    /** The invariants stated on this element. */
    public List<Invariant> invariantsOf(int element) {
        List<Invariant> out = new ArrayList<>();
        for (int i = invariantAt[element]; i < invariantAt[element + 1]; i += 3) {
            out.add(new Invariant(words[invariants[i]], word(invariants[i + 1]),
                    word(invariants[i + 2])));
        }
        return out;
    }

    private String word(int id) {
        return id < 0 ? null : words[id];
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Rows in, index out.
     *
     * <p>Elements arrive in document order within a structure, because that
     * is the order {@code ordinal} gives them and the order a parent is known
     * in before its children. A parent is derived from the path rather than
     * taken from the caller: a row's {@code parent_id} says the same thing,
     * and deriving it here is what lets an index built from packages and one
     * built from rows be compared without either being given the answer.
     */
    public static final class Builder {

        private final Map<String, Integer> dictionary = new HashMap<>();
        private final List<String> words = new ArrayList<>();
        private final List<String> canonicals = new ArrayList<>();
        private final List<Row> rows = new ArrayList<>();
        private final List<Integer> typeCodes = new ArrayList<>();
        private final List<Integer> invariants = new ArrayList<>();
        private final Map<String, Integer> firstElementOf = new LinkedHashMap<>();
        private final Map<String, Integer> byPath = new HashMap<>();
        private int canonical = -1;
        private Row open;
        private String openPath;

        private static final class Row {
            int canonical;
            int path;
            int parent = -1;
            short min;
            short max;
            int typeAt;
            int invariantAt;
            byte bindingStrength;
            int bindingValueSet = -1;
            int fixedValue = -1;
            int patternValue = -1;
        }

        /**
         * Start a structure. Everything added after this belongs to it until
         * the next one.
         */
        public Builder structure(String canonical) {
            close();
            this.canonical = canonicals.size();
            canonicals.add(canonical);
            byPath.clear();
            return this;
        }

        /**
         * Add an element to the open structure.
         *
         * @param max the maximum, or {@link #UNBOUNDED}
         */
        public Builder element(String path, int min, int max, List<String> types,
                String bindingStrength, String bindingValueSet, String fixed, String pattern) {
            if (canonical < 0) {
                throw new IllegalStateException(
                        "an element arrived before any structure was opened: " + path);
            }
            close();
            Row row = new Row();
            row.canonical = canonical;
            row.path = intern(path);
            row.min = clamp(min);
            row.max = max == UNBOUNDED ? (short) UNBOUNDED : clamp(max);
            row.typeAt = typeCodes.size();
            row.invariantAt = invariants.size();
            for (String type : types) {
                typeCodes.add(intern(type));
            }
            row.bindingStrength = strength(bindingStrength);
            row.bindingValueSet = bindingValueSet == null ? -1 : intern(bindingValueSet);
            row.fixedValue = fixed == null ? -1 : intern(fixed);
            row.patternValue = pattern == null ? -1 : intern(pattern);
            int cut = path.lastIndexOf('.');
            if (cut > 0) {
                row.parent = byPath.getOrDefault(path.substring(0, cut), -1);
            }
            open = row;
            openPath = path;
            return this;
        }

        /** Add an invariant to the element last added. */
        public Builder invariant(String key, String severity, String expression) {
            if (open == null) {
                throw new IllegalStateException(
                        "an invariant arrived before any element: " + key);
            }
            invariants.add(intern(key));
            invariants.add(severity == null ? -1 : intern(severity));
            invariants.add(expression == null ? -1 : intern(expression));
            return this;
        }

        public DefinitionIndex build() {
            close();
            return new DefinitionIndex(this);
        }

        private void close() {
            if (open == null) {
                return;
            }
            firstElementOf.putIfAbsent(canonicals.get(open.canonical), rows.size());
            byPath.put(openPath, rows.size());
            rows.add(open);
            open = null;
            openPath = null;
        }

        private static short clamp(int occurs) {
            return (short) Math.min(Short.MAX_VALUE, Math.max(0, occurs));
        }

        private static byte strength(String declared) {
            if (declared == null) {
                return NO_BINDING;
            }
            return switch (declared) {
                case "required" -> REQUIRED;
                case "extensible" -> EXTENSIBLE;
                case "preferred" -> PREFERRED;
                default -> EXAMPLE;
            };
        }

        private int intern(String value) {
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
}
