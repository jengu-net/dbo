package cloud.jengu.dbo.tenant;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who a new file of secrets is encrypted to, as SOPS decides it: the
 * repository's {@code .sops.yaml}, its first creation rule whose path
 * matches, and where there is none, the tenant's own Transit key alone.
 *
 * <p>The writer wraps only through the deployment's Vault and only under the
 * tenant's own key, as reading does, so a rule naming anything else — another
 * Transit key, a key type the writer has no means to use — is refused with
 * what it named, rather than written to recipients nobody asked for.
 */
final class SopsRules {

    /** The recipients of one file: the tenant's Transit key, and the owner's age keys. */
    record Recipients(String vaultAddress, String mount, String key, List<String> age) {

        Recipients {
            age = List.copyOf(age);
        }
    }

    /** What a rule may name that this writer has no means to wrap with. */
    private static final Set<String> UNUSABLE = Set.of("pgp", "kms", "gcp_kms",
            "azure_keyvault", "hckms", "key_groups");

    private SopsRules() {
    }

    /**
     * @param sopsYaml  the repository's {@code .sops.yaml}, or null where it has none
     * @param path      the file's path in the repository
     * @param vault     the deployment's Vault, as an address SOPS can reach
     * @param mount     where its Transit engine is mounted
     * @param tenantKey the tenant's own Transit key
     * @throws IllegalArgumentException when the rule for the path names what
     *                                  this writer cannot wrap with
     */
    static Recipients forPath(String sopsYaml, String path, URI vault, String mount,
            String tenantKey) {
        Recipients alone = new Recipients(vault.toString(), mount, tenantKey, List.of());
        if (sopsYaml == null || sopsYaml.isBlank()) {
            return alone;
        }
        Object read;
        try {
            read = new Load(LoadSettings.builder().build()).loadFromString(sopsYaml);
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException(".sops.yaml cannot be read: "
                    + unreadable.getMessage(), unreadable);
        }
        if (!(read instanceof Map<?, ?> config)
                || !(config.get("creation_rules") instanceof List<?> rules)) {
            return alone;
        }
        for (Object one : rules) {
            if (!(one instanceof Map<?, ?> rule)) {
                continue;
            }
            Object regex = rule.get("path_regex");
            if (regex != null && !Pattern.compile(String.valueOf(regex)).matcher(path).find()) {
                continue;
            }
            return recipientsOf(rule, path, vault, mount, tenantKey);
        }
        return alone;
    }

    private static Recipients recipientsOf(Map<?, ?> rule, String path, URI vault, String mount,
            String tenantKey) {
        for (Object named : rule.keySet()) {
            if (UNUSABLE.contains(String.valueOf(named))) {
                throw new IllegalArgumentException(".sops.yaml encrypts " + path + " to '"
                        + named + "', which this deployment cannot wrap a data key with: it "
                        + "wraps through its own Vault, and the owner's age keys");
            }
        }
        String address = vault.toString();
        for (String uri : listOf(rule.get("hc_vault_transit_uri"))) {
            // <address>/v1/<mount>/keys/<key>
            int at = uri.indexOf("/v1/");
            int keys = uri.lastIndexOf("/keys/");
            if (at < 0 || keys < at) {
                throw new IllegalArgumentException(".sops.yaml names the Transit key '" + uri
                        + "', which is not <address>/v1/<mount>/keys/<key>");
            }
            String named = uri.substring(keys + "/keys/".length());
            String engine = uri.substring(at + "/v1/".length(), keys);
            if (!named.equals(tenantKey) || !engine.equals(mount)) {
                throw new IllegalArgumentException(".sops.yaml encrypts " + path + " to the "
                        + "Transit key '" + engine + "/" + named + "', and this tenant's "
                        + "secrets are wrapped by '" + mount + "/" + tenantKey + "' alone");
            }
            address = uri.substring(0, at);
        }
        return new Recipients(address, mount, tenantKey, listOf(rule.get("age")));
    }

    /** A rule's value as a list: SOPS takes a comma-separated string or a list. */
    private static List<String> listOf(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> many) {
            many.forEach(one -> values.add(String.valueOf(one).strip()));
        } else if (value != null) {
            for (String one : String.valueOf(value).split(",")) {
                if (!one.isBlank()) {
                    values.add(one.strip());
                }
            }
        }
        return values;
    }
}
