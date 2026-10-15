package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file of secrets written as SOPS writes one, and read back by the reader
 * that reads what SOPS itself wrote. The reference tool's own reading of it is
 * proven where Vault and SOPS both run, in the harness.
 */
class AFileOfSecretsIsWrittenAsSopsWritesOneTest {

    private static final URI VAULT = URI.create("http://vault:8200");

    private static Map<String, String> secrets() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("a", "first-pa55");
        secrets.put("b", "second-π");
        return secrets;
    }

    /** What the Transit key would be handed: the data key, kept so the test can open the file. */
    private static final class Wrapped {
        byte[] dataKey;

        String wrap(byte[] key) {
            this.dataKey = key.clone();
            return "vault:v1:not-really";
        }
    }

    @Test
    @DisplayName("a file written with a and b opens with its data key to a and b, its metadata "
            + "naming the Transit key it was wrapped by")
    @Proving(DboPromises.TEN_A_DEPLOYMENT_WRITES_AN_OWNERS_SECRETS_AS_SOPS_DOES)
    void whatIsWrittenOpens() {
        Wrapped wrapped = new Wrapped();
        byte[] file = SopsFile.write(secrets(), new SopsRules.Recipients(VAULT.toString(),
                "transit", "tenant-clinic", List.of()), wrapped::wrap, Instant.now());
        SopsFile read = SopsFile.parse("x.sops.json", file);
        assertEquals(List.of("a", "b"), read.names());
        assertEquals(secrets(), read.open(wrapped.dataKey));
        assertEquals(List.of(new SopsFile.VaultRecipient("transit", "tenant-clinic",
                "vault:v1:not-really")), read.vault());
        String text = new String(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("first-pa55") || text.contains("second-π"),
                "a value is in the file in the clear");
    }

    @Test
    @DisplayName("the data key is encrypted to each age recipient, so the owner opens the file "
            + "with their own key and no Vault")
    @Proving(DboPromises.TEN_A_DEPLOYMENT_WRITES_AN_OWNERS_SECRETS_AS_SOPS_DOES)
    void theOwnersAgeKeyOpensIt() throws Exception {
        KeyPair owner = new com.exceptionfactory.jagged.x25519.X25519KeyPairGenerator()
                .generateKeyPair();
        String recipient = owner.getPublic().toString();
        assertTrue(recipient.startsWith("age1"), recipient);
        Wrapped wrapped = new Wrapped();
        byte[] file = SopsFile.write(secrets(), new SopsRules.Recipients(VAULT.toString(),
                "transit", "tenant-clinic", List.of(recipient)), wrapped::wrap, Instant.now());
        Map<?, ?> sops = (Map<?, ?>) ((Map<?, ?>) cloud.jengu.dbo.core.wire.RecordWire.read(
                new String(file, StandardCharsets.UTF_8))).get("sops");
        String armoured = String.valueOf(((Map<?, ?>) ((List<?>) sops.get("age")).get(0))
                .get("enc"));
        assertEquals(recipient, ((Map<?, ?>) ((List<?>) sops.get("age")).get(0))
                .get("recipient"));
        ByteBuffer opened = ByteBuffer.allocate(64);
        try (ReadableByteChannel channel = new com.exceptionfactory.jagged.framework.armor
                .ArmoredDecryptingChannelFactory().newDecryptingChannel(
                        Channels.newChannel(new java.io.ByteArrayInputStream(
                                armoured.getBytes(StandardCharsets.US_ASCII))),
                        List.of(com.exceptionfactory.jagged.x25519
                                .X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(
                                        owner.getPrivate().toString())))) {
            while (channel.read(opened) > 0) {
                // read to the end
            }
        }
        byte[] dataKey = java.util.Arrays.copyOf(opened.array(), opened.position());
        assertArrayEquals(wrapped.dataKey, dataKey, "the owner's key opens another data key");
        assertEquals(secrets(), SopsFile.parse("x.sops.json", file).open(dataKey));
    }

    @Test
    @DisplayName(".sops.yaml rules are followed by path: a path with an age recipient carries "
            + "it, one without carries the Transit key alone, and a rule the writer cannot use "
            + "is refused with what it named")
    @Proving(DboPromises.TEN_A_DEPLOYMENT_WRITES_AN_OWNERS_SECRETS_AS_SOPS_DOES)
    void theRulesAreFollowedByPath() {
        String rules = """
                creation_rules:
                  - path_regex: secrets/owner/.*
                    hc_vault_transit_uri: http://vault:8200/v1/transit/keys/tenant-clinic
                    age: age1first, age1second
                  - path_regex: secrets/pgp/.*
                    pgp: ABCDEF
                  - path_regex: secrets/other/.*
                    hc_vault_transit_uri: http://vault:8200/v1/transit/keys/tenant-elsewhere
                  - hc_vault_transit_uri: http://vault:8200/v1/transit/keys/tenant-clinic
                """;
        SopsRules.Recipients owner = SopsRules.forPath(rules, "secrets/owner/lab.sops.json",
                URI.create("http://deployment:8200"), "transit", "tenant-clinic");
        assertEquals(List.of("age1first", "age1second"), owner.age());
        assertEquals("http://vault:8200", owner.vaultAddress());
        assertEquals(List.of(), SopsRules.forPath(rules, "secrets/lab.sops.json", VAULT,
                "transit", "tenant-clinic").age());
        assertEquals(List.of(), SopsRules.forPath(null, "secrets/lab.sops.json", VAULT,
                "transit", "tenant-clinic").age());

        IllegalArgumentException pgp = assertThrows(IllegalArgumentException.class,
                () -> SopsRules.forPath(rules, "secrets/pgp/x.sops.json", VAULT, "transit",
                        "tenant-clinic"));
        assertTrue(pgp.getMessage().contains("'pgp'"), pgp.getMessage());
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class,
                () -> SopsRules.forPath(rules, "secrets/other/x.sops.json", VAULT, "transit",
                        "tenant-clinic"));
        assertTrue(other.getMessage().contains("tenant-elsewhere"), other.getMessage());
    }

    @Test
    @DisplayName("a tampered value, or a tampered MAC, in a written file is refused on reading")
    @Proving(DboPromises.TEN_A_DEPLOYMENT_WRITES_AN_OWNERS_SECRETS_AS_SOPS_DOES)
    void aTamperedFileIsRefused() {
        Wrapped wrapped = new Wrapped();
        String text = new String(SopsFile.write(secrets(), new SopsRules.Recipients(
                        VAULT.toString(), "transit", "tenant-clinic", List.of()), wrapped::wrap,
                Instant.now()), StandardCharsets.UTF_8);
        String a = field(text, "a", 0);
        String b = field(text, "b", 0);
        String swapped = text.replace(a, "@@").replace(b, a).replace("@@", b);
        assertThrows(IllegalArgumentException.class, () -> SopsFile.parse("swapped",
                swapped.getBytes(StandardCharsets.UTF_8)).open(wrapped.dataKey));
        String mac = field(text, "mac", 0);
        String forged = text.replace(mac, field(text, "a", 0));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SopsFile.parse("forged", forged.getBytes(StandardCharsets.UTF_8))
                        .open(wrapped.dataKey));
        assertTrue(refused.getMessage().contains("MAC"), refused.getMessage());
    }

    private static String field(String json, String name, int occurrence) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + name + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        for (int i = 0; i <= occurrence; i++) {
            assertTrue(m.find(), name + " #" + i + " is not in " + json);
        }
        return m.group(1).replace("\\n", "\n");
    }
}
