package cloud.jengu.dbo.fleet;

import cloud.jengu.dbo.core.wire.RecordWire;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Container entrypoint: one read of the fleet, printed as JSON, and exit.
 *
 * <p>Configured by the deployment and never by code, the way the operator
 * is: the nodes and the deployment's token from the environment, and the
 * per-tenant secrets from a directory — one file per tenant code, which is
 * what a mounted Secret looks like from inside a pod.
 *
 * <ul>
 *   <li>{@code DBO_FLEET_NODES} — {@code name=http://host:port,...}</li>
 *   <li>{@code DBO_OPS_TOKEN} — the token every node was given for its
 *       runtime questions</li>
 *   <li>{@code DBO_FLEET_CREDENTIAL_DIR} — a directory whose files are named
 *       by tenant code and hold that tenant's client secret</li>
 *   <li>{@code DBO_FLEET_CLIENT_ID} — the client those secrets belong to;
 *       {@code tenant-bootstrap} unless said otherwise, because that is the
 *       credential a deployment already holds per tenant and it already
 *       carries the fleet scope</li>
 *   <li>{@code DBO_FLEET_HOLDER} — {@code person}, {@code automation},
 *       {@code retry}, {@code nobody} or {@code any}; any unless said</li>
 * </ul>
 *
 * <p>Exit is zero whenever a reading was produced. An unreachable node is a
 * line in the reading, not a failure of the read: the read exists to say
 * which nodes did not answer.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        List<Node> nodes = nodes(require("DBO_FLEET_NODES"), require("DBO_OPS_TOKEN"));
        Credentials credentials = fromDirectory(Path.of(require("DBO_FLEET_CREDENTIAL_DIR")),
                env("DBO_FLEET_CLIENT_ID", "tenant-bootstrap"));
        String holder = env("DBO_FLEET_HOLDER", "any");
        Reading reading = new FleetReader(nodes, credentials)
                .read(new FleetReader.RunFilter(null, null, holder, null));
        System.out.println(RecordWire.write(reading));
    }

    /** {@code name=uri,name=uri}: the deployment's names, the deployment's addresses. */
    static List<Node> nodes(String spec, String opsToken) {
        List<Node> out = new ArrayList<>();
        for (String entry : spec.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                throw new IllegalArgumentException("DBO_FLEET_NODES entries are name=uri; got '"
                        + entry + "'");
            }
            out.add(new Node(entry.substring(0, eq).trim(),
                    URI.create(entry.substring(eq + 1).trim()), opsToken));
        }
        return out;
    }

    /** One file per tenant code, holding the secret; nothing else is read. */
    static Credentials fromDirectory(Path directory, String clientId) throws IOException {
        Map<String, Credentials.Credential> byTenant = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String code = file.getFileName().toString();
                    // a mounted Secret's dotfiles are the mount's own bookkeeping
                    if (code.startsWith(".")) {
                        continue;
                    }
                    byTenant.put(code, new Credentials.Credential(clientId,
                            Files.readString(file).strip()));
                }
            }
        }
        return Credentials.of(byTenant);
    }

    private static String env(String name, String fallback) {
        return Optional.ofNullable(System.getenv(name)).filter(v -> !v.isBlank()).orElse(fallback);
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable " + name);
        }
        return value;
    }
}
