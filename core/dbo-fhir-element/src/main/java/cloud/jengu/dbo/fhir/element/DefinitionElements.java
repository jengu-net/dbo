package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.definitions.DefinitionElement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A definition's snapshot, taken apart into the rows a checker reads
 * (REQ-DBO-VER-A-DEFINITION-IS-EXPANDED-WHEN-IT-ARRIVES).
 *
 * <p>Everything here is done once, when a definition arrives, because the
 * answer is the same every time it is read and the reader is a database:
 * which key a choice appears under, which members of an array a slice claims,
 * what each element may hold and what it is bound to. What comes out is a row
 * per element with a Postgres jsonpath already on it.
 *
 * <p><b>No toolchain.</b> A snapshot is an ordered list of elements with
 * dotted paths, and taking it apart is reading JSON — the same finding as
 * REQ-DBO-VER-DEFINITIONS-INDEXED-WITHOUT-THE-TOOLCHAIN, for the same reason:
 * a definition arriving at a tenant is exactly what the tenant does not hold
 * yet, so needing a worker context to read one is a bootstrap that has to be
 * broken somewhere. The definitions a version publishes carry their snapshot;
 * a differential-only profile is snapshotted by its face before it gets here.
 *
 * <p><b>What it refuses.</b> An element it cannot locate is named and refused
 * rather than dropped, because a checker that silently holds no row for an
 * element enforces nothing about it and says so to nobody. Measured over the
 * R4 and R5 cores, what that comes to is slices discriminated by
 * {@code resolve()} — a reference followed to another record, which is a join
 * rather than a path, and the one shape the design expected to owe the
 * database rather than the expression.
 */
public final class DefinitionElements {

    private DefinitionElements() {}

    /** An element that could not be located, and why — never silently dropped. */
    public record Refusal(String elementId, String why) {}

    /** What a definition's snapshot came to. */
    public record Expansion(
            String canonical,
            String version,
            String type,
            String kind,
            List<DefinitionElement> elements,
            List<Refusal> refusals) {}

    /**
     * @param structureDefinitionJson a StructureDefinition WITH its snapshot
     * @throws IllegalArgumentException if it has none — snapshotting is the
     *         caller's, and happens once, on arrival
     */
    public static Expansion of(byte[] structureDefinitionJson) {
        Object sd = Json.parse(new String(structureDefinitionJson, StandardCharsets.UTF_8));
        String canonical = text(sd, "url");
        Object snapshot = child(sd, "snapshot");
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "the definition has no snapshot, so there is nothing to expand: " + canonical);
        }
        List<Object> elements = array(snapshot, "element");
        Map<String, Object> byId = new LinkedHashMap<>();
        for (Object element : elements) {
            byId.put(idOf(element), element);
        }

        List<DefinitionElement> rows = new ArrayList<>(elements.size());
        List<Refusal> refusals = new ArrayList<>();
        for (Object element : elements) {
            String id = idOf(element);
            try {
                rows.add(row(id, element, byId, elements));
            } catch (Untranslatable why) {
                refusals.add(new Refusal(id, why.getMessage()));
                rows.add(unenforceable(id, element, why.getMessage()));
            }
        }
        return new Expansion(canonical, text(sd, "version"), text(sd, "type"),
                text(sd, "kind"), List.copyOf(rows), List.copyOf(refusals));
    }

    // ------------------------------------------------------------- one row

    private static DefinitionElement row(String id, Object element, Map<String, Object> byId,
            List<Object> snapshot) {
        String path = text(element, "path");
        int cut = id.lastIndexOf('.');
        String parentId = cut < 0 ? null : id.substring(0, cut);
        if (parentId != null && !byId.containsKey(parentId)) {
            throw new Untranslatable("its parent " + parentId + " is not in the snapshot");
        }
        return new DefinitionElement(id, path, parentId,
                cut < 0 ? List.of() : steps(id, path, element, byId, snapshot),
                min(element), max(element), types(element),
                valueUnder(element, "fixed"), valueUnder(element, "pattern"),
                text(child(element, "binding"), "strength"),
                text(child(element, "binding"), "valueSet"), null);
    }

    /**
     * A row for an element nothing can locate: what it says, with no way to
     * find it and the reason why, so a checker reports it rather than passing
     * everything at it.
     */
    private static DefinitionElement unenforceable(String id, Object element, String why) {
        int cut = id.lastIndexOf('.');
        return new DefinitionElement(id, text(element, "path"),
                cut < 0 ? null : id.substring(0, cut), List.of(),
                min(element), maxOrUnbounded(element), types(element),
                valueUnder(element, "fixed"), valueUnder(element, "pattern"),
                text(child(element, "binding"), "strength"),
                text(child(element, "binding"), "valueSet"), why);
    }

    private static Integer maxOrUnbounded(Object element) {
        try {
            return max(element);
        } catch (Untranslatable notACount) {
            return null;
        }
    }

    /**
     * Where this element is found, from an instance of its parent.
     *
     * <p>One step normally. Several for a choice, which is one element under
     * one of several keys. One with a predicate for a slice, which is some of
     * the members of an array and is told from the others by what the
     * definition says discriminates them.
     */
    private static List<String> steps(String id, String path, Object element,
            Map<String, Object> byId, List<Object> snapshot) {
        String segment = path.substring(path.lastIndexOf('.') + 1);
        String slice = sliceNameOf(id);
        if (slice == null) {
            return keysOf(segment, element).stream().map(key -> step(key, null)).toList();
        }
        return sliceStep(id, segment, slice, byId, snapshot);
    }

    /**
     * A slice's step: the array its base element names, narrowed to the
     * members this slice claims.
     *
     * <p>A choice sliced by type is the exception that needs no predicate at
     * all: the concrete key IS the discrimination, and the slice is named for
     * it — {@code Observation.value[x]:valueQuantity} is the members under
     * {@code valueQuantity}, of which JSON permits one.
     */
    private static List<String> sliceStep(String id, String segment, String slice,
            Map<String, Object> byId, List<Object> snapshot) {
        String slicedId = id.substring(0, id.lastIndexOf(':'));
        Object slicing = child(byId.get(slicedId), "slicing");
        List<Object> discriminators = slicing == null ? List.of() : array(slicing, "discriminator");
        if (discriminators.isEmpty()) {
            // A named element rather than a slice: the profile gives one
            // element a name without saying that the element repeats or how
            // its repeats differ — R4's own catalog and genetics profiles do
            // it. There is one of it and nothing to tell apart, so it is
            // located exactly as the element it names.
            return keysOf(segment, byId.get(id)).stream()
                    .map(key -> step(key, null)).toList();
        }
        boolean choice = segment.endsWith("[x]");
        String key = segment;
        List<String> predicates = new ArrayList<>();
        for (Object discriminator : discriminators) {
            String kind = text(discriminator, "type");
            String at = text(discriminator, "path");
            if (at == null || at.isBlank()) {
                at = "$this";
            }
            if (at.contains("(")) {
                throw new Untranslatable(
                        "it is discriminated by " + at + ", which follows a reference "
                        + "rather than a path");
            }
            switch (kind == null ? "" : kind) {
                case "value", "pattern" -> {
                    if (choice && "$this".equals(at)) {
                        key = concreteKey(segment, slice, byId.get(id));
                    } else {
                        predicates.add(discriminatingValue(id, at, byId, snapshot));
                    }
                }
                case "type" -> {
                    if (!choice || !"$this".equals(at)) {
                        throw new Untranslatable(
                                "it is discriminated by the type standing at " + at
                                + ", and only a choice says its type in the key");
                    }
                    key = concreteKey(segment, slice, byId.get(id));
                }
                case "exists" -> predicates.add(existence(id, at, byId));
                default -> throw new Untranslatable(
                        "it is discriminated by '" + kind + "', which is not a kind this "
                        + "store can express");
            }
        }
        return List.of(step(key, predicates.isEmpty() ? null : String.join(" && ", predicates)));
    }

    /**
     * Every key an element can appear under: its own name, or — for a choice —
     * the name with each type it may take, since that is how JSON spells a
     * choice out.
     */
    private static List<String> keysOf(String segment, Object element) {
        if (!segment.endsWith("[x]")) {
            return List.of(segment);
        }
        String base = segment.substring(0, segment.length() - 3);
        List<String> keys = new ArrayList<>();
        for (Object type : array(element, "type")) {
            keys.add(base + capitalized(text(type, "code")));
        }
        if (keys.isEmpty()) {
            throw new Untranslatable("a choice that names no type appears under no key");
        }
        return List.copyOf(keys);
    }

    /**
     * The key a slice of a choice appears under.
     *
     * <p>From the type the slice narrows to, not from its name: a slice of a
     * choice exists to pin one of the types, so its own type list says which
     * key it is. The name usually says the same thing — {@code value[x]} sliced
     * to {@code valueQuantity} — but not always, and R4's own family-history
     * genetics profile names one {@code BornAge} for the key {@code bornAge}.
     * Reading the type rather than the name is the difference between a rule
     * and a naming convention.
     */
    private static String concreteKey(String segment, String slice, Object element) {
        List<String> keys = keysOf(segment, element);
        if (keys.size() == 1) {
            return keys.get(0);
        }
        String base = segment.substring(0, segment.length() - 3);
        if (slice.startsWith(base) && slice.length() > base.length()) {
            return slice;
        }
        throw new Untranslatable("it is told apart by its type and narrows " + segment
                + " to " + keys.size() + " of them, so it appears under no one key");
    }

    /**
     * The value this slice's members carry at the discriminating path.
     *
     * <p>The definition states it as a fixed or pattern value somewhere at or
     * above that path, so the search is for the deepest element that states
     * one and then a walk into the value itself — a pattern on
     * {@code component.code} carries the {@code coding.code} a discriminator
     * names, and there is no element of its own for it.
     *
     * <p>The exception is an extension slice, where the discriminating url is
     * not in the profile at all: it is the canonical of the extension the
     * slice says it is, and an extension's url is its definition's url. Held
     * here because the alternative is refusing 109 slices of the two cores
     * over a fact the specification guarantees.
     */
    private static String discriminatingValue(String id, String at, Map<String, Object> byId,
            List<Object> snapshot) {
        List<String> segments = "$this".equals(at) ? List.of() : List.of(at.split("\\."));
        Object stated = null;
        int statedAt = -1;
        for (Object element : snapshot) {
            String candidate = idOf(element);
            if (!candidate.startsWith(id + ".") && !candidate.equals(id)) {
                continue;
            }
            List<String> reached = candidate.equals(id) ? List.of()
                    : List.of(withoutSliceNames(candidate.substring(id.length() + 1)).split("\\."));
            if (reached.size() > segments.size() || !segments.subList(0, reached.size()).equals(reached)) {
                continue;
            }
            String here = valueUnder(element, "fixed");
            if (here == null) {
                here = valueUnder(element, "pattern");
            }
            if (here == null) {
                continue;
            }
            if (reached.size() > statedAt) {
                stated = Json.parse(here);
                statedAt = reached.size();
            } else if (reached.size() == statedAt) {
                throw new Untranslatable("two of it fix a value at " + at
                        + ", and a path cannot say which");
            }
        }
        if (stated != null) {
            return predicate(accessors(at),
                    within(stated, segments.subList(statedAt, segments.size()), at));
        }
        String profile = extensionProfileOf(byId.get(id));
        if ("url".equals(at) && profile != null) {
            return accessors(at).isEmpty() ? "@ == " + Json.quoted(profile)
                    : "@" + accessors(at) + " == " + Json.quoted(profile);
        }
        throw new Untranslatable("nothing in it fixes a value at " + at + " to tell it apart by");
    }

    /** The remainder of a discriminating path, read inside a stated value. */
    private static Object within(Object value, List<String> remaining, String at) {
        Object here = value;
        for (String segment : remaining) {
            here = only(here, at);
            here = child(here, segment);
            if (here == null) {
                throw new Untranslatable("the value it is told apart by states nothing at " + at);
            }
        }
        return only(here, at);
    }

    private static Object only(Object node, String at) {
        if (node instanceof List<?> members) {
            if (members.size() != 1) {
                throw new Untranslatable("the value it is told apart by at " + at
                        + " is one of several, and a path compares one");
            }
            return members.get(0);
        }
        return node;
    }

    /**
     * What the member under test must hold to be this slice's.
     *
     * <p>A fixed value is one comparison. A pattern is a structure, and what
     * it means is that the member CONTAINS it — which as a predicate is every
     * leaf of the pattern compared where it sits, since containment of one
     * branch is exactly the conjunction of its leaves.
     */
    private static String predicate(String accessor, Object value) {
        return switch (value) {
            case String text -> "@" + accessor + " == " + Json.quoted(text);
            case Number number -> "@" + accessor + " == " + number;
            case Boolean flag -> "@" + accessor + " == " + flag;
            case Map<?, ?> structure -> {
                List<String> leaves = new ArrayList<>();
                for (Map.Entry<?, ?> entry : structure.entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    if (name.startsWith("_") || "id".equals(name) || "extension".equals(name)) {
                        continue; // the pattern's own machinery, not a value to match
                    }
                    leaves.add(predicate(accessor + "." + Json.quoted(name),
                            only(entry.getValue(), name)));
                }
                if (leaves.isEmpty()) {
                    throw new Untranslatable("the value it is told apart by holds nothing "
                            + "to compare");
                }
                yield String.join(" && ", leaves);
            }
            default -> throw new Untranslatable("the value it is told apart by is nothing "
                    + "a path can compare");
        };
    }

    private static String withoutSliceNames(String path) {
        StringBuilder plain = new StringBuilder();
        for (String segment : path.split("\\.", -1)) {
            int marker = segment.indexOf(':');
            plain.append(plain.isEmpty() ? "" : ".")
                    .append(marker < 0 ? segment : segment.substring(0, marker));
        }
        return plain.toString();
    }

    private static String existence(String id, String at, Map<String, Object> byId) {
        Object element = byId.get(id + "." + at);
        if (element == null) {
            throw new Untranslatable("it is told apart by whether " + at
                    + " is there, and it does not say so");
        }
        Integer max = max(element);
        if (max != null && max == 0) {
            return "!exists (@" + accessors(at) + ")";
        }
        if (min(element) >= 1) {
            return "exists (@" + accessors(at) + ")";
        }
        throw new Untranslatable("it is told apart by whether " + at
                + " is there, and it neither requires nor forbids it");
    }

    private static String extensionProfileOf(Object element) {
        for (Object type : array(element, "type")) {
            List<String> profiles = Json.strings(type, "profile");
            if (!profiles.isEmpty()) {
                return profiles.get(0);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ jsonpath

    /**
     * {@code [*]} on every step, deliberately.
     *
     * <p>A member accessor alone returns an array AS ONE VALUE, so counting it
     * counts one where a document holds three, and every cardinality above
     * {@code 1} would pass. {@code [*]} on something that is not an array is
     * that one value, and on an absent key it is nothing, so the same step
     * serves an element however the instance happens to hold it.
     */
    private static String step(String key, String predicate) {
        String located = "$." + Json.quoted(key) + "[*]";
        return predicate == null ? located : located + " ? (" + predicate + ")";
    }

    /** {@code code.coding.code} as accessors on the member under test. */
    private static String accessors(String path) {
        if ("$this".equals(path)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("\\.")) {
            sb.append('.').append(Json.quoted(segment));
        }
        return sb.toString();
    }

    // ----------------------------------------------------------- the parts

    private static List<DefinitionElement.Type> types(Object element) {
        List<DefinitionElement.Type> types = new ArrayList<>();
        for (Object type : array(element, "type")) {
            types.add(new DefinitionElement.Type(text(type, "code"),
                    Json.strings(type, "profile"), Json.strings(type, "targetProfile")));
        }
        return List.copyOf(types);
    }

    private static String valueUnder(Object element, String prefix) {
        if (!(element instanceof Map<?, ?> map)) {
            return null;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (name.length() > prefix.length() && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return Json.render(entry.getValue());
            }
        }
        return null;
    }

    private static int min(Object element) {
        Object value = child(element, "min");
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Integer max(Object element) {
        String value = text(element, "max");
        if (value == null || "*".equals(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException notANumber) {
            throw new Untranslatable("it may occur '" + value + "' times, which is no count");
        }
    }

    private static String idOf(Object element) {
        String id = text(element, "id");
        return id != null ? id : text(element, "path");
    }

    private static String sliceNameOf(String id) {
        String segment = id.substring(id.lastIndexOf('.') + 1);
        int marker = segment.indexOf(':');
        return marker < 0 ? null : segment.substring(marker + 1);
    }

    private static String capitalized(String type) {
        if (type == null || type.isEmpty()) {
            throw new Untranslatable("a type with no code appears under no key");
        }
        return Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    private static String text(Object node, String field) {
        Object value = child(node, field);
        return value == null ? null : String.valueOf(value);
    }

    private static Object child(Object node, String field) {
        return node instanceof Map<?, ?> map ? map.get(field) : null;
    }

    private static List<Object> array(Object node, String field) {
        return node instanceof Map<?, ?> ? Json.array(node, field) : List.of();
    }

    /** Thrown where an element is found to be unlocatable, caught into a refusal. */
    private static final class Untranslatable extends RuntimeException {
        Untranslatable(String why) {
            super(why);
        }
    }
}
