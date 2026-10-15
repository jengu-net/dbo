package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.wire.RecordWire;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The deployment's Vault, as far as the store uses it: its Transit engine,
 * unwrapping a data key under a tenant's own key.
 *
 * <p><b>The store talks to it itself.</b> It logs in as its own workload —
 * Kubernetes auth with the projected service account token, or a token the
 * deployment hands it — and holds a policy scoped to the tenants' Transit
 * keys. Whatever provisions the tenants creates those keys and is not asked to
 * decrypt anything.
 *
 * <p><b>Only the Vault the deployment names.</b> A file of secrets says which
 * Vault wrapped it; that is the owner's file speaking, and the store reaches no
 * address an owner wrote.
 */
public final class VaultTransit {

    private final URI address;
    private final String mount;
    private final Supplier<String> token;
    private final HttpClient http;

    /**
     * @param address the Vault, as the deployment names it
     * @param mount   where its Transit engine is mounted
     * @param token   a token the store acts under, asked for at each use
     */
    public VaultTransit(URI address, String mount, Supplier<String> token) {
        this.address = address;
        this.mount = mount == null || mount.isBlank() ? "transit" : mount;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * A token handed over by the deployment, read from a file at each use so
     * that a token rotated beside the store is the one used.
     */
    public static Supplier<String> tokenFile(Path file) {
        return () -> {
            try {
                return Files.readString(file).strip();
            } catch (IOException unreadable) {
                throw new IllegalStateException("the Vault token at " + file
                        + " cannot be read: " + unreadable.getMessage(), unreadable);
            }
        };
    }

    /**
     * Logged in with Kubernetes auth as the store's own service account, and
     * logged in again before the lease ends.
     *
     * @param authMount where the Kubernetes auth method is mounted
     * @param role      the role bound to the store's service account
     * @param jwt       the projected service account token
     */
    public static Supplier<String> kubernetes(URI address, String authMount, String role,
            Path jwt) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Object lock = new Object();
        String[] held = new String[1];
        Instant[] until = new Instant[] {Instant.MIN};
        return () -> {
            synchronized (lock) {
                if (held[0] != null && Instant.now().isBefore(until[0])) {
                    return held[0];
                }
                try {
                    String body = RecordWire.write(Map.of("role", role,
                            "jwt", Files.readString(jwt).strip()));
                    Map<?, ?> answer = send(http, HttpRequest.newBuilder(address.resolve(
                                    "/v1/auth/" + (authMount == null ? "kubernetes" : authMount)
                                            + "/login"))
                            .POST(HttpRequest.BodyPublishers.ofString(body)), "log in");
                    Map<?, ?> auth = (Map<?, ?>) answer.get("auth");
                    held[0] = String.valueOf(auth.get("client_token"));
                    long lease = ((Number) auth.get("lease_duration")).longValue();
                    // Again at two thirds of the lease, so a token never
                    // expires between being handed out and being used.
                    until[0] = Instant.now().plusSeconds(Math.max(1, lease * 2 / 3));
                    return held[0];
                } catch (IOException unreadable) {
                    throw new IllegalStateException("the service account token at " + jwt
                            + " cannot be read: " + unreadable.getMessage(), unreadable);
                }
            }
        };
    }

    /**
     * A data key Transit wrapped under a key, unwrapped.
     *
     * @throws IllegalArgumentException when Vault will not unwrap it, saying
     *                                  what Vault said
     */
    byte[] unwrap(String key, String wrapped) {
        Map<?, ?> answer = send(http, HttpRequest.newBuilder(address.resolve(
                        "/v1/" + mount + "/decrypt/" + key))
                .header("X-Vault-Token", token.get())
                .POST(HttpRequest.BodyPublishers.ofString(
                        RecordWire.write(Map.of("ciphertext", wrapped)))), "unwrap under '"
                + key + "'");
        Map<?, ?> data = (Map<?, ?>) answer.get("data");
        // Transit takes and gives plaintext in base64, and the data key is
        // what SOPS handed it.
        return Base64.getDecoder().decode(String.valueOf(data.get("plaintext")));
    }

    /** The same Vault and login, with Transit mounted elsewhere; this one for null. */
    VaultTransit withMount(String other) {
        return other == null || other.equals(mount) ? this
                : new VaultTransit(address, other, token);
    }

    /** A key wrapped under a Transit key: Vault's ciphertext for it. */
    String wrap(String key, byte[] plain) {
        Map<?, ?> answer = send(http, HttpRequest.newBuilder(address.resolve(
                        "/v1/" + mount + "/encrypt/" + key))
                .header("X-Vault-Token", token.get())
                .POST(HttpRequest.BodyPublishers.ofString(RecordWire.write(Map.of(
                        "plaintext", Base64.getEncoder().encodeToString(plain))))),
                "wrap under '" + key + "'");
        return String.valueOf(((Map<?, ?>) answer.get("data")).get("ciphertext"));
    }

    /**
     * The same key wrapped again under the Transit key's latest version,
     * without it leaving Vault: how a rotation reaches what was wrapped before.
     */
    String rewrap(String key, String wrapped) {
        Map<?, ?> answer = send(http, HttpRequest.newBuilder(address.resolve(
                        "/v1/" + mount + "/rewrap/" + key))
                .header("X-Vault-Token", token.get())
                .POST(HttpRequest.BodyPublishers.ofString(
                        RecordWire.write(Map.of("ciphertext", wrapped)))),
                "rewrap under '" + key + "'");
        return String.valueOf(((Map<?, ?>) answer.get("data")).get("ciphertext"));
    }

    /** The Vault this reaches. */
    URI address() {
        return address;
    }

    /** Where this Transit engine is mounted, as a file of secrets names it. */
    String mount() {
        return mount;
    }

    private static Map<?, ?> send(HttpClient http, HttpRequest.Builder request, String what) {
        HttpResponse<String> answered;
        try {
            answered = http.send(request.header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException unreachable) {
            throw new IllegalArgumentException("Vault cannot be reached to " + what + ": "
                    + unreachable.getMessage(), unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted asking Vault to " + what);
        }
        if (answered.statusCode() / 100 != 2) {
            throw new IllegalArgumentException("Vault would not " + what + ": "
                    + answered.statusCode() + " " + errorsOf(answered.body()));
        }
        return (Map<?, ?>) RecordWire.read(answered.body());
    }

    private static String errorsOf(String body) {
        try {
            if (RecordWire.read(body) instanceof Map<?, ?> said && said.get("errors") != null) {
                return String.valueOf(said.get("errors"));
            }
        } catch (RuntimeException unreadable) {
            // the status says enough
        }
        return "";
    }
}
