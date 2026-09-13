package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** `Json.str(root, "face")` and friends: a key read off the spec's root. */
    private static final Pattern READ_FROM_ROOT = Pattern.compile(
            "Json\\.\\w+\\(\\s*root\\s*,\\s*\"(\\w+)\"");

    /** `root.get("audit")`: how the policies are read off the same root. */
    private static final Pattern TAKEN_FROM_ROOT = Pattern.compile(
            "root\\.get\\(\\s*\"(\\w+)\"\\s*\\)");

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
        Set<String> carried = new TreeSet<>();
        carried.addAll(keysIn(source("core/dbo-tenant", "tenant/TenantSpec.java"), READ_FROM_ROOT));
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
                        + "field, the tenant. Declare it in tenantregistration-crd.yaml");

        Set<String> unexplained = new TreeSet<>(declared);
        unexplained.removeAll(carried);
        unexplained.removeAll(THE_OPERATORS_OWN.keySet());
        assertEquals(Set.of(), unexplained,
                "the custom resource declares a field no spec parser reads. Either the parser "
                        + "lost it — in which case a cluster is accepting a field that now does "
                        + "nothing — or it is the operator's own and belongs in THE_OPERATORS_OWN "
                        + "with a reason");
    }

    /** The spec block's own property names, at the one indent they sit at. */
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
        Matcher m = Pattern.compile("^                (\\w+):", Pattern.MULTILINE)
                .matcher(yaml.substring(spec, status));
        while (m.find()) {
            properties.add(m.group(1));
        }
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
