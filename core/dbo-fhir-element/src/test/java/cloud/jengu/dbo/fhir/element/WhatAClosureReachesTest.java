package cloud.jengu.dbo.fhir.element;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How much of a version's definitions a tenant's declared types actually reach.
 *
 * <p>The whole of item 025 rests on this number and nothing had produced it.
 * A face costs 225 MB because every structure a release carries is parsed and
 * held; the proposal is to hold the closure of what a tenant declared instead.
 * Whether that is worth building is the ratio between the two, and it is a
 * count over definitions that already exist.
 *
 * <p><b>Composition is followed, reference is not.</b> An element typed
 * {@code HumanName} reaches {@code HumanName}. An element typed
 * {@code Reference(Condition)} reaches {@code Reference} and stops: a target
 * profile is a constraint on what may be pointed at, not a thing this tenant
 * operates on, and {@code Condition} enters only where the tenant declares it.
 * That is what makes a closure small, and it is the one rule the count would
 * be meaningless without.
 *
 * <p><b>No toolchain.</b> The walk reads the carried packages as JSON, which
 * is the same claim the index makes — if this needed a worker context to
 * answer, the thing it is measuring could not exist.
 */
final class WhatAClosureReachesTest {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    /** What sample/world/tenants/hogwarts.json declares it operates on. */
    private static final Set<String> HOGWARTS = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "Organization", "Person", "Practitioner", "PractitionerRole",
            "Patient", "Encounter", "Observation"));

    /** One structure, reduced to the edges a closure follows. */
    private record Structure(
            String url, String kind, String derivation, String baseDefinition,
            int elements, Set<String> composes, Set<String> requiredValueSets) {
    }

    @Test
    void count() throws IOException {
        List<FaceRootPackages.Definition> carried =
                FaceRootPackages.definitionsFor("r5", Set.of("StructureDefinition"));

        Map<String, Structure> corpus = new LinkedHashMap<>();
        long corpusElements = 0;
        for (FaceRootPackages.Definition one : carried) {
            Structure s = read(one.document());
            if (s == null || s.url() == null) {
                continue;
            }
            corpus.put(s.url(), s);
            corpusElements += s.elements();
        }

        Map<String, Integer> byKind = new TreeMap<>();
        for (Structure s : corpus.values()) {
            byKind.merge(s.kind() == null ? "?" : s.kind(), 1, Integer::sum);
        }

        Set<String> closure = walk(corpus, HOGWARTS);
        Set<String> nonPrimitive = new LinkedHashSet<>();
        long closureElements = 0;
        Set<String> closureValueSets = new LinkedHashSet<>();
        for (String url : closure) {
            Structure s = corpus.get(url);
            if (s == null) {
                continue;
            }
            closureElements += s.elements();
            closureValueSets.addAll(s.requiredValueSets());
            if (!"primitive-type".equals(s.kind())) {
                nonPrimitive.add(url);
            }
        }

        Set<String> corpusValueSets = new LinkedHashSet<>();
        for (Structure s : corpus.values()) {
            corpusValueSets.addAll(s.requiredValueSets());
        }

        System.out.println();
        System.out.println("=== what hogwarts' 11 declared types reach, r5 ===");
        System.out.printf("corpus structures        %6d   by kind %s%n", corpus.size(), byKind);
        System.out.printf("corpus elements          %6d%n", corpusElements);
        System.out.printf("corpus required VS       %6d%n", corpusValueSets.size());
        System.out.println();
        System.out.printf("closure structures       %6d   (%4.1f%% of corpus)%n",
                closure.size(), 100.0 * closure.size() / corpus.size());
        System.out.printf("  of which non-primitive %6d%n", nonPrimitive.size());
        System.out.printf("closure elements         %6d   (%4.1f%% of corpus)%n",
                closureElements, 100.0 * closureElements / corpusElements);
        System.out.printf("closure required VS      %6d   (%4.1f%% of corpus)%n",
                closureValueSets.size(), 100.0 * closureValueSets.size() / corpusValueSets.size());
        System.out.println();

        // Per declared type, what it alone drags in — which says whether the
        // closure is one fat type or spread evenly.
        // The kernel: what EVERY declared type reaches, which is the
        // datatypes a resource is made of rather than anything a tenant chose.
        Set<String> kernel = null;
        Map<String, Set<String>> alone = new LinkedHashMap<>();
        for (String type : HOGWARTS) {
            if (!corpus.containsKey(PREFIX + type)) {
                continue;
            }
            Set<String> one = walk(corpus, Set.of(type));
            alone.put(type, one);
            if (kernel == null) {
                kernel = new LinkedHashSet<>(one);
            } else {
                kernel.retainAll(one);
            }
        }
        System.out.printf("kernel structures        %6d   elements %6d%n",
                kernel.size(), elementsIn(corpus, kernel));
        System.out.printf("above the kernel         %6d   elements %6d   for 11 declared types%n",
                closure.size() - kernel.size(),
                closureElements - elementsIn(corpus, kernel));
        System.out.println();

        // The upper bound: a face whose tenants between them declare every
        // resource the version has. If this is near the corpus, narrowing is
        // a per-tenant win only; if it is far below, it is a per-face one.
        Set<String> everyResource = new LinkedHashSet<>();
        for (Structure s2 : corpus.values()) {
            if ("resource".equals(s2.kind()) && s2.url().startsWith(PREFIX)) {
                everyResource.add(s2.url().substring(PREFIX.length()));
            }
        }
        Set<String> widest = walk(corpus, everyResource);
        System.out.printf("every resource declared  %6d structures %6d elements (%4.1f%% of corpus)%n",
                widest.size(), elementsIn(corpus, widest),
                100.0 * elementsIn(corpus, widest) / corpusElements);
        System.out.println();

        // Three assertions, each on the FINDING rather than on the figure, so
        // that a package upgrade moving a count by a few per cent does not
        // fail while a change that broke the closure rule does.

        // Narrowing is worth building. A tenant of a realistic shape reaches
        // well under a fifth of what a face holds; measured, 7.2%.
        org.junit.jupiter.api.Assertions.assertTrue(closureElements * 100 < corpusElements * 15,
                "a tenant's closure has stopped being small: " + closureElements
                        + " of " + corpusElements + " elements, where the design assumes under 15%");

        // What every type shares is a kernel of datatypes, not a specification.
        // If this grows to resemble the corpus, composition is being followed
        // where it should have stopped — a reference walked, most likely.
        org.junit.jupiter.api.Assertions.assertTrue(elementsIn(corpus, kernel) * 100 < corpusElements * 10,
                "the shared kernel has stopped being a kernel: " + elementsIn(corpus, kernel)
                        + " of " + corpusElements + " elements");

        // And it is a PER-TENANT win, not a per-face one. A face whose tenants
        // between them declare every resource holds nearly the whole corpus,
        // so a single index shared by a face's tenants would save nothing.
        // This is the assertion that decides the shape of the thing.
        org.junit.jupiter.api.Assertions.assertTrue(elementsIn(corpus, widest) * 100 > corpusElements * 80,
                "declaring every resource no longer reaches most of the corpus ("
                        + elementsIn(corpus, widest) + " of " + corpusElements
                        + ") — if that is real, one index per FACE would do and "
                        + "the per-tenant index is not worth its complexity");

        System.out.println("=== each declared type alone ===");
        for (String type : HOGWARTS) {
            Set<String> one = alone.get(type);
            if (one == null) {
                System.out.printf("  %-22s not carried%n", type);
                continue;
            }
            System.out.printf("  %-22s %4d structures %6d elements   above kernel: %d / %d%n",
                    type, one.size(), elementsIn(corpus, one),
                    one.size() - kernel.size(),
                    elementsIn(corpus, one) - elementsIn(corpus, kernel));
        }
        System.out.println();
    }

    private static long elementsIn(Map<String, Structure> corpus, Set<String> urls) {
        long total = 0;
        for (String url : urls) {
            Structure s = corpus.get(url);
            if (s != null) {
                total += s.elements();
            }
        }
        return total;
    }

    /** Everything the seed types reach by composition and by base. */
    private static Set<String> walk(Map<String, Structure> corpus, Set<String> seedTypes) {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String type : seedTypes) {
            String url = PREFIX + type;
            if (corpus.containsKey(url) && reached.add(url)) {
                pending.add(url);
            }
        }
        while (!pending.isEmpty()) {
            Structure s = corpus.get(pending.poll());
            if (s == null) {
                continue;
            }
            for (String edge : s.composes()) {
                if (corpus.containsKey(edge) && reached.add(edge)) {
                    pending.add(edge);
                }
            }
            if (s.baseDefinition() != null
                    && corpus.containsKey(s.baseDefinition())
                    && reached.add(s.baseDefinition())) {
                pending.add(s.baseDefinition());
            }
        }
        return reached;
    }

    /**
     * The edges one structure offers, read straight off the bytes.
     *
     * <p>A targeted token scan rather than a tree: the snapshot of a resource
     * is most of the document, and building a map of it per structure to read
     * two fields per element is the very cost this is measuring.
     */
    private static Structure read(byte[] document) throws IOException {
        String url = null;
        String kind = null;
        String derivation = null;
        String base = null;
        int elements = 0;
        Set<String> composes = new LinkedHashSet<>();
        Set<String> requiredValueSets = new LinkedHashSet<>();

        String bindingStrength = null;
        String bindingValueSet = null;

        try (JsonParser p = new JsonFactory().createParser(document)) {
            // One frame per open container, carrying the field that opened it.
            // A scalar inside an array has no field name of its own, so the
            // frame is what names it — which is the whole reason this is a
            // stack of containers rather than of field names.
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
                    Frame enclosing = frames.peek();
                    if (t == JsonToken.START_OBJECT
                            && enclosing != null && enclosing.array()
                            && "element".equals(enclosing.field())
                            && snapshotDepth >= 0) {
                        elements++;
                        bindingStrength = null;
                        bindingValueSet = null;
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
                    if (bindingStrength != null && bindingValueSet != null) {
                        if ("required".equals(bindingStrength)) {
                            requiredValueSets.add(bindingValueSet);
                        }
                        bindingStrength = null;
                        bindingValueSet = null;
                    }
                    field = null;
                    continue;
                }

                // A scalar. In an array it is named by the array's own field.
                Frame in = frames.peek();
                String named = in != null && in.array() ? in.field() : field;
                String value = p.getValueAsString();
                field = null;
                if (named == null || value == null) {
                    continue;
                }
                if (frames.size() == 1) {
                    switch (named) {
                        case "url" -> url = value;
                        case "kind" -> kind = value;
                        case "derivation" -> derivation = value;
                        case "baseDefinition" -> base = value;
                        default -> { }
                    }
                    continue;
                }
                if (snapshotDepth < 0) {
                    continue;
                }
                if ("code".equals(named) && "type".equals(frameField(frames, 1))) {
                    // A primitive's own type is FHIRPath's, not a structure:
                    // "http://hl7.org/fhirpath/System.String" resolves to
                    // nothing in the corpus and must not be invented as a url.
                    composes.add(value.startsWith("http") ? value : PREFIX + value);
                } else if ("strength".equals(named) && "binding".equals(frameField(frames, 0))) {
                    bindingStrength = value;
                } else if ("valueSet".equals(named) && "binding".equals(frameField(frames, 0))) {
                    bindingValueSet = value;
                }
            }
        }
        return new Structure(url, kind, derivation, base, elements,
                composes, requiredValueSets);
    }

    /** One open container and the field that opened it. */
    private record Frame(String field, boolean array) {
    }

    /** The field of the nth open container from the top, or null. */
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
