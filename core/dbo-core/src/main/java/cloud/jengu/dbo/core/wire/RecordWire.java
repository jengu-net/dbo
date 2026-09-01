package cloud.jengu.dbo.core.wire;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Records on a wire, mapped as they are declared.
 *
 * <p><b>The record as declared, never field by field.</b> {@code Run} gained
 * milestones and named inputs after the type existed, and
 * {@code StepDeclaration} and {@code Declarations.Declared} have both grown
 * since they were written. A hand-written encoder freezes the shape at the
 * moment somebody wrote it and drops whatever is added next — silently, at
 * the far end, where it looks like a step that reported nothing rather than
 * like an encoder that forgot. So this walks {@link RecordComponent}s: a
 * component added tomorrow travels tomorrow, and a component whose type this
 * cannot carry fails loudly here instead.
 *
 * <p><b>An unknown field is not a fault.</b> Decoding ignores what it does
 * not recognise, because a peer one version ahead sends fields this side has
 * not learned yet and refusing them would make every additive change to a
 * record a breaking change to the surface.
 *
 * <p>Payload bytes travel as base64 and are not parsed on the way past:
 * re-deriving a record from a parsed resource drops what the model has no
 * field for, which is the byte-shaped seam's whole argument, reached here
 * from the transport side.
 */
public final class RecordWire {

    private RecordWire() {
    }

    /** A value as JSON text — records, enums, collections, instants, bytes. */
    public static String write(Object value) {
        return Json.render(encode(value));
    }

    /** JSON text as a tree, for a caller that will decode fields of it. */
    public static Object read(String json) {
        return Json.parse(json);
    }

    // ── encoding ────────────────────────────────────────────────────────

    /** One value as a tree of maps, lists and scalars. */
    public static Object encode(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Number n -> n;
            case byte[] bytes -> Base64.getEncoder().encodeToString(bytes);
            case Instant instant -> instant.toString();
            case Enum<?> constant -> constant.name();
            case Optional<?> optional -> optional.map(RecordWire::encode).orElse(null);
            case Map<?, ?> map -> encodeMap(map);
            case Iterable<?> items -> encodeList(items);
            default -> {
                if (value.getClass().isRecord()) {
                    yield encodeRecord(value);
                }
                throw new IllegalArgumentException("this wire cannot carry a "
                        + value.getClass().getName() + " — it carries records, enums, "
                        + "collections and scalars, and a new one has to say how it travels");
            }
        };
    }

    private static Map<String, Object> encodeRecord(Object record) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value;
            try {
                value = component.getAccessor().invoke(record);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read " + component.getName()
                        + " of " + record.getClass().getSimpleName(), e);
            }
            // Absent rather than null: a shorter frame, and decoding treats
            // the two the same way on the far side.
            if (value != null) {
                out.put(component.getName(), encode(value));
            }
        }
        return out;
    }

    private static Map<String, Object> encodeMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), encode(value)));
        return out;
    }

    private static List<Object> encodeList(Iterable<?> items) {
        List<Object> out = new ArrayList<>();
        items.forEach(item -> out.add(encode(item)));
        return out;
    }

    // ── decoding ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static <T> T decode(Object node, Class<T> type) {
        return (T) decode(node, (Type) type);
    }

    /** A map keyed by string, of whatever the values are — a run's inputs. */
    public static <V> Map<String, V> decodeMap(Object node, Class<V> value) {
        Map<String, V> out = new LinkedHashMap<>();
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> out.put(String.valueOf(key), decode(entry, value)));
        }
        return out;
    }

    /** A list of whatever the elements are — the answer {@code poll} gives. */
    public static <E> List<E> decodeList(Object node, Class<E> element) {
        List<E> out = new ArrayList<>();
        if (node instanceof List<?> items) {
            items.forEach(item -> out.add(decode(item, element)));
        }
        return out;
    }

    private static Object decode(Object node, Type type) {
        if (type instanceof ParameterizedType parameterized) {
            return decodeParameterized(node, parameterized);
        }
        if (!(type instanceof Class<?> target)) {
            throw new IllegalArgumentException("this wire cannot decode " + type);
        }
        if (node == null) {
            return defaultOf(target);
        }
        if (target == String.class) {
            return String.valueOf(node);
        }
        if (target == long.class || target == Long.class) {
            return ((Number) node).longValue();
        }
        if (target == int.class || target == Integer.class) {
            return ((Number) node).intValue();
        }
        if (target == double.class || target == Double.class) {
            return ((Number) node).doubleValue();
        }
        if (target == boolean.class || target == Boolean.class) {
            return node instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(node));
        }
        if (target == byte[].class) {
            return Base64.getDecoder().decode(String.valueOf(node));
        }
        if (target == Instant.class) {
            return Instant.parse(String.valueOf(node));
        }
        if (target.isEnum()) {
            return constantOf(target, String.valueOf(node));
        }
        if (target.isRecord()) {
            return decodeRecord(node, target);
        }
        throw new IllegalArgumentException("this wire cannot decode a "
                + target.getName());
    }

    private static Object decodeParameterized(Object node, ParameterizedType type) {
        Class<?> raw = (Class<?>) type.getRawType();
        Type[] arguments = type.getActualTypeArguments();
        if (raw == Optional.class) {
            return Optional.ofNullable(node == null ? null : decode(node, arguments[0]));
        }
        if (raw == Map.class || raw == java.util.LinkedHashMap.class) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (node instanceof Map<?, ?> map) {
                map.forEach((key, value) ->
                        out.put(String.valueOf(key), decode(value, arguments[1])));
            }
            return out;
        }
        if (raw == List.class || raw == Set.class) {
            List<Object> items = new ArrayList<>();
            if (node instanceof List<?> array) {
                array.forEach(item -> items.add(decode(item, arguments[0])));
            }
            return raw == Set.class ? new LinkedHashSet<>(items) : items;
        }
        throw new IllegalArgumentException("this wire cannot decode a "
                + raw.getName() + " of " + List.of(arguments));
    }

    private static Object decodeRecord(Object node, Class<?> type) {
        if (!(node instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException(type.getSimpleName()
                    + " arrives as an object, and this arrived as " + node);
        }
        RecordComponent[] components = type.getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] parameters = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            parameters[i] = components[i].getType();
            // A field this side has not learned about is ignored by never
            // being asked for; one it knows and the peer omitted decodes as
            // absent, which is what the encoder means by leaving it out.
            arguments[i] = decode(fields.get(components[i].getName()),
                    components[i].getGenericType());
        }
        try {
            Constructor<?> canonical = type.getDeclaredConstructor(parameters);
            canonical.setAccessible(true);
            return canonical.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot rebuild a " + type.getSimpleName()
                    + " from the wire", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constantOf(Class<?> type, String name) {
        return Enum.valueOf((Class<Enum>) type, name);
    }

    /**
     * What an absent field decodes to. A primitive has no null to be, so it
     * takes its zero — which is what {@code versionId} and a milestone's
     * {@code position} already mean when nobody set them.
     */
    private static Object defaultOf(Class<?> target) {
        if (!target.isPrimitive()) {
            return null;
        }
        if (target == boolean.class) {
            return false;
        }
        if (target == int.class) {
            return 0;
        }
        if (target == long.class) {
            return 0L;
        }
        if (target == double.class) {
            return 0d;
        }
        throw new IllegalArgumentException("no default for " + target);
    }
}
