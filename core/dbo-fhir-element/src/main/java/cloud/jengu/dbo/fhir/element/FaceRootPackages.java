package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A version's definitions as documents, for a face root to hold as records.
 *
 * <p>The same bytes {@link CarriedDefinitions#contextFor} parses into a worker
 * context, read the same way and no other: a face root is the one place the
 * carried packages are opened, so that what a tenant validates against can be
 * rows it replicated rather than an object graph a node loaded. The
 * terminology packages are skipped here for the same reason they are skipped
 * there — their concepts are tenant data, imported into the store once and
 * consulted there.
 *
 * <p>Narrative is stripped, as the context loader strips it: a generated
 * rendering of the definition is not the definition, and on a core package it
 * is most of the bytes.
 */
public final class FaceRootPackages {

    /** One definition, ready to be written: its type and its bytes. */
    public record Definition(String typeName, String url, byte[] document) {
    }

    private FaceRootPackages() {
    }

    /**
     * Every definition of the given types the face's packages carry.
     *
     * @param types the types the root declares — what it does not declare it
     *              cannot hold, and a package resource of any other type is
     *              left in the package rather than refused
     */
    /** The types a terminology package contributes: its systems and value sets, not its own structures. */
    private static final Set<String> TERMINOLOGY_TYPES = Set.of("CodeSystem", "ValueSet");

    public static List<Definition> definitionsFor(String face, Set<String> types) {
        List<Definition> out = new ArrayList<>();
        // The terminology package carries value sets and code systems the core
        // package carries too, under the same canonical; a canonical is one
        // record, and the terminology package's is the one held — it is the
        // terminology authority, which is why the baseline was read from it.
        // Read first, so that the core's copy of a url it already answered
        // for is the one skipped.
        List<CarriedDefinitions.Carried> packages = new ArrayList<>(CarriedDefinitions.forVersion(face));
        packages.sort(java.util.Comparator.comparing(c -> c.name().startsWith("hl7.terminology") ? 0 : 1));
        Set<String> seen = new java.util.HashSet<>();
        for (CarriedDefinitions.Carried carried : packages) {
            // The terminology packages carry the version's value sets and code
            // systems beside a few structures of their own; a root holds the
            // former as records, so that a subscriber takes them from it
            // rather than from a package, and leaves the latter to the
            // definition packages, whose structures are the version's.
            Set<String> wanted = carried.name().startsWith("hl7.terminology")
                    ? types.stream().filter(TERMINOLOGY_TYPES::contains).collect(java.util.stream.Collectors.toSet())
                    : types;
            if (wanted.isEmpty()) {
                continue;
            }
            try {
                for (NpmPackage.PackageResourceInformation indexed
                        : CarriedDefinitions.indexed(carried, wanted.toArray(new String[0]))) {
                    JsonObject json;
                    try (var in = CarriedDefinitions.read(indexed)) {
                        json = JsonParser.parseObject(in);
                    }
                    String typeName = json.asString("resourceType");
                    if (typeName == null || !wanted.contains(typeName)) {
                        continue;
                    }
                    if (json.asString("url") != null && !seen.add(typeName + "|" + json.asString("url"))) {
                        continue;
                    }
                    json.remove("text");
                    out.add(new Definition(typeName, json.asString("url"),
                            JsonParser.compose(json).getBytes(StandardCharsets.UTF_8)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + carried.id(), e);
            }
        }
        return List.copyOf(out);
    }
}
