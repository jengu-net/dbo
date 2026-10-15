package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Files SOPS itself wrote, read by the store's own reader.
 *
 * <p>The fixtures were encrypted by {@code sops} 3.13.1 for an age recipient,
 * and each data key below is what {@code age} unwrapped from that recipient.
 * The store never reads an age recipient — the owner keeps one so the file
 * opens without the store — so the key is given here, and what is proven is
 * the rest of the format: a value encrypted under its path, and the MAC.
 */
class ASecretsFileIsReadAsSopsWroteItTest {

    private static final byte[] SECRETS_KEY = HexFormat.of().parseHex(
            "aa2ec08da838c757830a9ac46317059471388e4b4d6722e5da9dff453529f2f2");
    private static final byte[] TYPED_KEY = HexFormat.of().parseHex(
            "6f1487774bb90056da6f72421b6b6875c00391923c46ba46c47ac7eefe8da2a7");

    @Test
    @DisplayName("a file SOPS encrypted opens with its data key, value by value, and its "
            + "names are readable before it is opened")
    @Proving(DboPromises.TEN_AN_OWNERS_SECRETS_ARE_SEALED_IN_ITS_REPOSITORY)
    void aFileSopsWroteOpens() throws IOException {
        SopsFile file = SopsFile.parse("secrets.sops.json", fixture("secrets.sops.json"));
        assertEquals(List.of("lab-connection-password", "registry-token"), file.names());
        assertEquals(Map.of("lab-connection-password", "s3cr3t-π", "registry-token", "tok_9f8e"),
                file.open(SECRETS_KEY));
    }

    @Test
    @DisplayName("it does not open with another key, with a value moved to another name, or "
            + "with a value taken out")
    @Proving(DboPromises.TEN_AN_OWNERS_SECRETS_ARE_SEALED_IN_ITS_REPOSITORY)
    void aChangedFileDoesNotOpen() throws IOException {
        String text = new String(fixture("secrets.sops.json"), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> SopsFile.parse("secrets.sops.json",
                text.getBytes(StandardCharsets.UTF_8)).open(TYPED_KEY));

        String password = valueOf(text, "lab-connection-password");
        String token = valueOf(text, "registry-token");
        String swapped = text.replace(password, "@@").replace(token, password)
                .replace("@@", token);
        IllegalArgumentException moved = assertThrows(IllegalArgumentException.class,
                () -> SopsFile.parse("swapped", swapped.getBytes(StandardCharsets.UTF_8))
                        .open(SECRETS_KEY));
        assertTrue(moved.getMessage().contains("does not open"), moved.getMessage());

        String dropped = text.replaceFirst("\"registry-token\":\\s*\"[^\"]*\",", "");
        IllegalArgumentException mac = assertThrows(IllegalArgumentException.class,
                () -> SopsFile.parse("dropped", dropped.getBytes(StandardCharsets.UTF_8))
                        .open(SECRETS_KEY));
        assertTrue(mac.getMessage().contains("MAC"), mac.getMessage());
    }

    @Test
    @DisplayName("a value that is not a string at the top is refused by name, since a secret "
            + "is one value reached by its name")
    @Proving(DboPromises.TEN_AN_OWNERS_SECRETS_ARE_SEALED_IN_ITS_REPOSITORY)
    void onlyStringsAreSecrets() throws IOException {
        IllegalArgumentException typed = assertThrows(IllegalArgumentException.class,
                () -> SopsFile.parse("typed.sops.json", fixture("typed.sops.json"))
                        .open(TYPED_KEY));
        assertTrue(typed.getMessage().contains("'port' is a float"), typed.getMessage());
    }

    private static String valueOf(String text, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + name + "\":\\s*\"([^\"]*)\"").matcher(text);
        assertTrue(m.find(), name);
        return m.group(1);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = ASecretsFileIsReadAsSopsWroteItTest.class
                .getResourceAsStream("/sops/" + name)) {
            return in.readAllBytes();
        }
    }
}
