package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The custom resource declares every field a tenant spec may carry.
 *
 * <p>A registration is server-side applied, and the API server refuses a field
 * the schema does not declare — the whole registration, not the field. So a
 * spec model that gains a field the CRD does not gain too is not a field
 * quietly ignored in the cluster: it is a tenant that never exists. The first
 * one cost a tenant carrying {@code scim}, absent since the cluster path went
 * live, for a field the store itself reads and acts on.
 *
 * <p>It is the ordinary shape of a second place the same thing is named.
 * Nothing tells the person adding a field to the parser that a schema
 * elsewhere lists them, and the cost arrives later, in a cluster, to somebody
 * else. This reads both and refuses the disagreement.
 *
 * <p><b>Read rather than derived, deliberately.</b> Generating the schema from
 * the model would end the drift and would also decide the schema's shape —
 * patterns, enums, which fields are required — from Java types that do not
 * carry any of that. The CRD says more than the model does and is worth
 * writing by hand; what it must not do is say less.
 */
class ACrdDeclaresWhatASpecMayCarryTest {

    /**
     * `Json.str(root, "face")`, `Json.bool(d, "face")`: a key, and the object
     * it is read off.
     *
     * <p>Root-only is what this read for its first year, and the key it
     * therefore missed was `dependencies[].face` — read by the parser, absent
     * from the schema, found by hand rather than here. A pruned nested key is
     * the quieter half of the same failure: the tenant registers and comes up
     * without the face chain it named, where a missing root key at least
     * refuses the registration outright.
     *
     * <p><b>The object matters, not just the key.</b> Collecting bare names
     * and comparing sets looks like it widens this and does not: `face` is a
     * root field too, so the very key this exists for would have been found
     * declared and the schema would have passed missing it. So a read becomes
     * a PATH, and the schema is read as paths.
     */
    private static final Pattern READ_FROM_AN_OBJECT = Pattern.compile(
            "Json\\.\\w+\\(\\s*(\\w+)\\s*,\\s*\"(\\w+)\"");

    /** `root.get("audit")`: how the policies are read off the spec's root. */
    private static final Pattern TAKEN_FROM_ROOT = Pattern.compile(
            "root\\.get\\(\\s*\"(\\w+)\"\\s*\\)");

    /**
     * How the parser comes to hold a nested object, and under which key.
     *
     * <p>Three shapes, because they are the three the parser uses: a mapped
     * stream, an enhanced for, and a plain assignment. A fourth shape is not
     * silent — its variable stays unbound, and a read off an unbound variable
     * is refused by name rather than skipped.
     */
    private static final List<Pattern> BOUND_TO_A_KEY = List.of(
            // Json.array(root, "types").stream().map(t -> …
            Pattern.compile("Json\\.\\w+\\(\\s*\\w+\\s*,\\s*\"(?<key>\\w+)\"\\)"
                    + "\\s*\\.stream\\(\\)\\s*\\.map\\(\\s*(?<var>\\w+)\\s*->"),
            // for (Object one : Json.array(root, "steps"))
            Pattern.compile("for\\s*\\(\\s*[\\w.<>?\\[\\]]+\\s+(?<var>\\w+)\\s*:"
                    + "\\s*Json\\.\\w+\\(\\s*\\w+\\s*,\\s*\"(?<key>\\w+)\"\\)"),
            // Object scimNode = Json.objOpt(root, "scim");
            Pattern.compile("[\\w.<>?\\[\\]]+\\s+(?<var>\\w+)\\s*=\\s*"
                    + "Json\\.obj\\w*\\(\\s*\\w+\\s*,\\s*\"(?<key>\\w+)\"\\)"));


    /**
     * Read by the parser and never carried: a key it recognises in order to
     * refuse it. Declaring these in the CRD would invite the spelling the
     * parser exists to reject.
     */
    private static final Map<String, String> REFUSED = Map.of(
            "fhirVersion", "selects a face and is named 'face'; the parser refuses it by name");

    /**
     * Declared by the custom resource and not by the spec: the operator's own,
     * read when a registration is deleted rather than when a tenant is parsed.
     */
    private static final Map<String, String> THE_OPERATORS_OWN = Map.of(
            "deletionPolicy", "whether deleting the registration erases the tenant or retains it");

    @Test
    @DisplayName("every key a tenant spec may carry is declared in the custom resource, because "
            + "the API server refuses the whole registration over one it is not")
    void theCrdDeclaresEveryKeyTheParserAccepts() throws Exception {
        String parser = source("core/dbo-tenant", "tenant/TenantSpec.java");
        Map<String, String> under = boundObjects(parser);
        Set<String> carried = new TreeSet<>(pathsIn(parser, under));
        // The policies are read off the root and their blocks are free-form
        // below it, so only their root keys are held to the schema.
        carried.addAll(keysIn(source("core/dbo-policy", "policy/TenantPolicies.java"),
                TAKEN_FROM_ROOT));
        carried.removeAll(REFUSED.keySet());

        assertTrue(carried.size() > 10,
                "only " + carried + " keys were read out of the spec parsers, so this has "
                        + "stopped reading them rather than the spec having stopped carrying any");

        Set<String> declared = new TreeSet<>(crdSpecProperties());
        Set<String> missing = new TreeSet<>(carried);
        missing.removeAll(declared);
        assertEquals(Set.of(), missing,
                "the spec parser accepts a key the custom resource does not declare, so a "
                        + "tenant carrying it is refused at registration in full — not the "
                        + "field, the tenant — or, for a nested one, pruned and silently not "
                        + "there. Declare it in tenantregistration-crd.yaml");

        Set<String> unexplained = new TreeSet<>();
        for (String path : declared) {
            if (!path.contains(".") && !carried.contains(path)) {
                unexplained.add(path);
            }
        }
        unexplained.removeAll(THE_OPERATORS_OWN.keySet());
        assertEquals(Set.of(), unexplained,
                "the custom resource declares a field no spec parser reads. Either the parser "
                        + "lost it — in which case a cluster is accepting a field that now does "
                        + "nothing — or it is the operator's own and belongs in THE_OPERATORS_OWN "
                        + "with a reason");
    }

    /** Which key each nested object the parser holds was read out of. */
    private static Map<String, String> boundObjects(String parser) {
        Map<String, String> under = new java.util.TreeMap<>();
        under.put("root", "");
        for (Pattern shape : BOUND_TO_A_KEY) {
            Matcher m = shape.matcher(parser);
            while (m.find()) {
                under.put(m.group("var"), m.group("key"));
            }
        }
        return under;
    }

    /**
     * Every key the parser reads, as a path: `face`, `dependencies.face`.
     *
     * <p>A read off an object this could not bind is a failure rather than a
     * skip. The whole point is that a key the schema does not declare is
     * silent in the cluster, and a reader that quietly ignores what it cannot
     * follow reproduces that silence here.
     */
    private static Set<String> pathsIn(String parser, Map<String, String> under) {
        Set<String> paths = new TreeSet<>();
        Matcher m = READ_FROM_AN_OBJECT.matcher(parser);
        while (m.find()) {
            String object = m.group(1);
            assertTrue(under.containsKey(object),
                    "the parser reads \"" + m.group(2) + "\" off " + object + ", and this test "
                            + "cannot tell which key " + object + " came out of — so it cannot "
                            + "say whether the schema declares it. Bind it in BOUND_TO_A_KEY");
            String parent = under.get(object);
            paths.add(parent.isEmpty() ? m.group(2) : parent + "." + m.group(2));
        }
        return paths;
    }

    /**
     * Every property the spec block declares, as a path.
     *
     * <p>Walked rather than matched at one indent: a schema nests, and the
     * names under a nested `properties:` are exactly the ones a one-indent
     * reader cannot see. Only the keys directly under a `properties:` mapping
     * count — `type`, `items`, `required` and the rest are the schema's own
     * vocabulary rather than fields a tenant carries — and `items` does not
     * appear in the path, because a tenant does not write it either.
     */
    private static Set<String> crdSpecProperties() throws IOException {
        String yaml = Files.readString(
                repository().resolve("core/dbo-operator/src/main/resources/"
                        + "tenantregistration-crd.yaml"));
        int spec = yaml.indexOf("\n            spec:");
        int status = yaml.indexOf("\n            status:");
        assertTrue(spec > 0 && status > spec,
                "the custom resource no longer has a spec block followed by a status block, so "
                        + "this is reading the wrong shape rather than reading nothing");
        Set<String> properties = new TreeSet<>();
        // A STACK of the `properties:` mappings still open, each with the path
        // it belongs to. Keeping only the innermost loses the fields beside a
        // nested object on the way back out, which drops every top-level field
        // the moment one of them nests — wrong in exactly the direction that
        // reads as "the parser accepts a key the schema does not declare"
        // about keys the schema declares.
        java.util.Deque<int[]> open = new java.util.ArrayDeque<>();
        java.util.Deque<String> paths = new java.util.ArrayDeque<>();
        String last = "";
        Pattern anyKey = Pattern.compile("^\\s*(\\w+):");
        for (String line : yaml.substring(spec, status).split("\n")) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            int indent = line.indexOf(line.strip().charAt(0));
            while (!open.isEmpty() && indent <= open.peek()[0]) {
                open.pop();
                paths.pop();
            }
            Matcher key = anyKey.matcher(line);
            if (!key.find()) {
                continue;
            }
            if (!open.isEmpty() && indent == open.peek()[0] + 2) {
                String parent = paths.peek();
                last = parent.isEmpty() ? key.group(1) : parent + "." + key.group(1);
                properties.add(last);
            }
            if (key.group(1).equals("properties")) {
                open.push(new int[] {indent});
                paths.push(open.size() == 1 ? "" : last);
            }
        }
        assertTrue(properties.size() > 10,
                "only " + properties + " came out of the custom resource, so this has stopped "
                        + "reading its shape rather than the schema having stopped declaring "
                        + "fields");
        return properties;
    }

    private static Set<String> keysIn(String src, Pattern pattern) {
        Set<String> keys = new TreeSet<>();
        Matcher m = pattern.matcher(src);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static String source(String module, String withinPackage) throws IOException {
        return Files.readString(repository().resolve(module)
                .resolve("src/main/java/cloud/jengu/dbo").resolve(withinPackage));
    }

    /** The repository root, wherever the test happens to be run from. */
    private static Path repository() {
        Path here = Path.of("").toAbsolutePath();
        for (Path at = here; at != null; at = at.getParent()) {
            if (Files.isDirectory(at.resolve("core/dbo-operator"))) {
                return at;
            }
        }
        throw new IllegalStateException("the repository root is not above " + here);
    }
}
