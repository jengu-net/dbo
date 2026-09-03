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
 * <p>Acting is configured separately, and usually not at all:
 *
 * <ul>
 *   <li>{@code DBO_FLEET_SUPERVISE_CREDENTIAL_DIR} — per-tenant secrets for a
 *       credential carrying {@code supervise}, in the same shape as the
 *       reading ones. Without it this reader looks and does not touch, which
 *       is what most deployments want.</li>
 *   <li>{@code DBO_FLEET_SUPERVISE_CLIENT_ID} — the client those secrets
 *       belong to. It has no default: the reading default is the
 *       deployment's own per-tenant credential, and that one deliberately
 *       supervises nothing, so guessing a name here would guess wrong.</li>
 *   <li>{@code DBO_FLEET_ACT_TOKEN} — what a caller presents to ask the
 *       service to act, separate from {@code DBO_FLEET_TOKEN}. Unset, the
 *       act surface is not mounted.</li>
 * </ul>
 *
 * <p>As a command, {@code reopen <tenant> <run> <reason...>} makes one closed
 * run claimable again and exits non-zero if it did not happen — a read says
 * which nodes were silent and is still a read, but an act either landed or
 * did not, and a script has to be able to tell.</p>
 *
 * <p>Exit is zero whenever a reading was produced. An unreachable node is a
 * line in the reading, not a failure of the read: the read exists to say
 * which nodes did not answer.
 *
 * <p><b>Or a service.</b> Given {@code DBO_FLEET_LISTEN} ({@code host:port})
 * and {@code DBO_FLEET_TOKEN}, it serves {@code GET /fleet} instead, reading
 * the fleet afresh on every request and holding nothing between them — the
 * shape a pod takes. The same filter travels as query parameters:
 * {@code holder}, {@code process}, {@code step}, {@code limit}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        List<Node> nodes = nodes(require("DBO_FLEET_NODES"), require("DBO_OPS_TOKEN"));
        Credentials credentials = fromDirectory(Path.of(require("DBO_FLEET_CREDENTIAL_DIR")),
                env("DBO_FLEET_CLIENT_ID", "tenant-bootstrap"));
        FleetReader reader = new FleetReader(nodes, credentials, supervisory(),
                java.time.Duration.ofSeconds(10));
        if (args.length > 0 && "reopen".equals(args[0])) {
            if (args.length < 4) {
                throw new IllegalStateException(
                        "usage: reopen <tenant> <run-key> <reason...>");
            }
            FleetReader.Acted acted = reader.reopen(args[1], args[2],
                    String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
            System.out.println(RecordWire.write(acted));
            if (acted.outcome() != Reading.Outcome.ANSWERED) {
                System.exit(1);
            }
            return;
        }
        String listen = System.getenv("DBO_FLEET_LISTEN");
        if (listen == null || listen.isBlank()) {
            String holder = env("DBO_FLEET_HOLDER", "any");
            System.out.println(RecordWire.write(
                    reader.read(new FleetReader.RunFilter(null, null, holder, null))));
            return;
        }
        int colon = listen.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalStateException("DBO_FLEET_LISTEN is host:port; got '" + listen + "'");
        }
        try (FleetService service = new FleetService(reader, listen.substring(0, colon),
                Integer.parseInt(listen.substring(colon + 1)), require("DBO_FLEET_TOKEN"),
                System.getenv("DBO_FLEET_ACT_TOKEN"))) {
            service.start();
            // Startup says WHAT it is and WHERE it reads, and names no tenant:
            // the node names are the deployment's own words and carry nothing.
            // Whether this one can act is part of its resolved posture, and
            // the first thing somebody reading the log wants to know about a
            // process that could overturn work.
            LOG.info("started: component=dbo-fleet version={} jdk={} listen={} nodes={} "
                            + "acts={}",
                    version(), Runtime.version(), listen,
                    nodes.stream().map(Node::name).toList(), service.acts());
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> LOG.info("shutdown requested: component=dbo-fleet"), "dbo-shutdown"));
            Thread.currentThread().join();
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Main.class);

    /** The build's version, or a marker when running from a plain classpath. */
    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
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

    /**
     * The supervisory credentials, or none. No default client id: the reading
     * default is the deployment's own per-tenant credential, which supervises
     * nothing by design, so falling back to it would build a reader that
     * looks able to act and is refused by every tenant it asks.
     */
    static Credentials supervisory() throws IOException {
        String directory = System.getenv("DBO_FLEET_SUPERVISE_CREDENTIAL_DIR");
        String clientId = System.getenv("DBO_FLEET_SUPERVISE_CLIENT_ID");
        if (directory == null || directory.isBlank()) {
            return Credentials.none();
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("DBO_FLEET_SUPERVISE_CREDENTIAL_DIR is set and "
                    + "DBO_FLEET_SUPERVISE_CLIENT_ID is not; a supervisory secret belongs to "
                    + "a client, and this one has no sensible default");
        }
        return fromDirectory(Path.of(directory), clientId);
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
