package cloud.jengu.dbo.promise;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The composition seam: catalogues in, one graph out.
 *
 * <p>{@link #load(ClassLoader)} walks every {@link #INDEX} resource the
 * loader can see — dbo alone during dbo development, every product's jar at
 * the end of the day — and {@link #of(Class[])} stays for tools and tests
 * that want to be explicit. Either way a catalogue is read WHOLE via
 * {@code getEnumConstants()}: proven, planned and gap constants alike, never
 * a side effect of what happened to be class-loaded
 * (REQ-DBO-PRM-CATALOGUE-READ-WHOLE).
 */
public final class Registry {

    /** Where the processor writes and the registry reads: one name per line. */
    public static final String INDEX = "META-INF/promise/catalogues";

    private final List<Class<?>> catalogues;

    private Registry(List<Class<?>> catalogues) {
        this.catalogues = List.copyOf(catalogues);
    }

    /** Every catalogue registered on {@code loader}'s classpath. */
    public static Registry load(ClassLoader loader) {
        LinkedHashSet<Class<?>> found = new LinkedHashSet<>();
        try {
            Enumeration<URL> indexes = loader.getResources(INDEX);
            while (indexes.hasMoreElements()) {
                URL index = indexes.nextElement();
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(index.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (!line.isBlank()) {
                            found.add(catalogueClass(loader, line.strip(), index));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + INDEX, e);
        }
        return new Registry(new ArrayList<>(found));
    }

    /** The explicit form, for tools and tests. */
    public static Registry of(Class<?>... catalogues) {
        List<Class<?>> validated = new ArrayList<>();
        for (Class<?> catalogue : catalogues) {
            validated.add(validated(catalogue,
                    "registered explicitly as " + catalogue.getName()));
        }
        return new Registry(validated);
    }

    public List<Class<?>> catalogues() {
        return catalogues;
    }

    /** The composed graph over every registered catalogue. */
    public Model model() {
        return new Model(catalogues);
    }

    private static Class<?> catalogueClass(ClassLoader loader, String name, URL index) {
        try {
            return validated(Class.forName(name, false, loader), "named by " + index);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(index + " names '" + name
                    + "', which this classpath does not carry — a stale index is a build "
                    + "defect, not a skippable line", e);
        }
    }

    private static Class<?> validated(Class<?> catalogue, String origin) {
        if (!catalogue.isEnum()) {
            throw new IllegalArgumentException(catalogue.getName() + " (" + origin
                    + ") is not an enum — the constant's name is the code, and only an enum "
                    + "has constants to name");
        }
        if (catalogue.getAnnotation(Catalogue.class) == null) {
            throw new IllegalArgumentException(catalogue.getName() + " (" + origin
                    + ") carries no @Catalogue — the namespace half of every code is missing");
        }
        if (!Coded.class.isAssignableFrom(catalogue)) {
            throw new IllegalArgumentException(catalogue.getName() + " (" + origin
                    + ") implements none of the model interfaces — nothing here knows how "
                    + "to read it");
        }
        return catalogue;
    }

    /**
     * The composed graph: every promise, classification and area of every
     * registered catalogue, areas merged by code, the classification →
     * promise links inverted once so a promise's classifications are derived,
     * never declared (REQ-DBO-PRM-DOWN-LINKS-ONLY).
     */
    public static final class Model {

        private final List<Promise> promises = new ArrayList<>();
        private final List<Classified> classifications = new ArrayList<>();
        private final Map<String, MergedArea> areas = new LinkedHashMap<>();
        private final Map<Promise, List<Classified>> declaring = new LinkedHashMap<>();
        private final Map<Promise, String> namespaces = new LinkedHashMap<>();

        private Model(List<Class<?>> catalogues) {
            for (Class<?> catalogue : catalogues) {
                String namespace = catalogue.getAnnotation(Catalogue.class).namespace();
                for (Object constant : catalogue.getEnumConstants()) {
                    if (constant instanceof Promise promise) {
                        promises.add(promise);
                    }
                    if (constant instanceof Classified classified) {
                        classifications.add(classified);
                        for (Promise declared : classified.promises()) {
                            declaring.computeIfAbsent(declared, p -> new ArrayList<>())
                                    .add(classified);
                            if (declared.gap()) {
                                // A gap registers through the classification
                                // that noticed the hole; the namespace it is
                                // reported under is that catalogue's.
                                promises.add(declared);
                                namespaces.put(declared, namespace);
                            }
                        }
                    }
                    if (constant instanceof Area area) {
                        areas.merge(area.code(), new MergedArea(area.code(), area.title(),
                                new ArrayList<>(area.covers())), MergedArea::merged);
                    }
                }
            }
        }

        public List<Promise> promises() {
            return List.copyOf(promises);
        }

        public List<Classified> classifications() {
            return List.copyOf(classifications);
        }

        /** Same-code areas merged; conflicting titles refused, both named. */
        public List<Area> areas() {
            return List.copyOf(areas.values());
        }

        /** The derived inverse of the down-links. Empty for the undeclared. */
        public List<Classified> declaring(Promise promise) {
            return List.copyOf(declaring.getOrDefault(promise, List.of()));
        }

        /**
         * The promise's code as the composed report says it: a named
         * constant answers for itself; a gap is qualified by the namespace
         * of the catalogue whose classification declared it.
         */
        public String codeOf(Promise promise) {
            String namespace = namespaces.get(promise);
            return namespace == null ? promise.code() : namespace + "-" + promise.code();
        }

        private record MergedArea(String code, String title, List<Classified> covers)
                implements Area {

            MergedArea merged(MergedArea other) {
                if (!title.equals(other.title)) {
                    throw new IllegalStateException("area '" + code + "' is declared with two "
                            + "titles — '" + title + "' and '" + other.title + "' — and picking "
                            + "one would silently overrule the other catalogue");
                }
                List<Classified> union = new ArrayList<>(covers);
                union.addAll(other.covers);
                return new MergedArea(code, title, union);
            }
        }
    }
}
