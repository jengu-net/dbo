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
    public static List<Definition> definitionsFor(String face, Set<String> types) {
        List<Definition> out = new ArrayList<>();
        for (CarriedDefinitions.Carried carried : CarriedDefinitions.forVersion(face)) {
            if (carried.name().startsWith("hl7.terminology")) {
                continue;
            }
            try {
                NpmPackage npm = NpmPackage.fromPackage(CarriedDefinitions.open(carried));
                for (String name : npm.listResources(types.toArray(new String[0]))) {
                    JsonObject json;
                    try (var in = npm.loadResource(name)) {
                        json = JsonParser.parseObject(in);
                    }
                    String typeName = json.asString("resourceType");
                    if (typeName == null || !types.contains(typeName)) {
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
