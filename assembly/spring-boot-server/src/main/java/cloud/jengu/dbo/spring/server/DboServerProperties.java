package cloud.jengu.dbo.spring.server;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a deployment says, in the names a Spring application expects.
 *
 * <p>The runtime's activators read framework properties through
 * {@code BundleContext.getProperty}, and the whole set is visible in the
 * serving distribution's launcher, which maps environment variables onto the
 * same names. This maps Spring properties onto them, and
 * {@link #asFrameworkProperties()} is the mapping — one place, so that a
 * launcher flag and a Spring property cannot describe different deployments.
 *
 * <p><b>Only the management tenant is named here.</b> Every other tenant is
 * declared through a bean, for the reason the runtime gives: the management
 * tenant is declared by configuration rather than by a file in the watched
 * directory, so the loop that retracts tenants cannot retract the thing
 * recording retractions.
 */
@ConfigurationProperties("dbo")
@Data
public class DboServerProperties {

    /** Where the surfaces answer. */
    private Mount mount = Mount.SERVLET;

    /** The spec of the tenant this deployment's own history lives in. */
    private String managementSpec;

    /** The durable substrate a tenant's stream door is opened on, where there is one. */
    private Substrate substrate = new Substrate();

    private Tenants tenants = new Tenants();

    private Http http = new Http();

    private Auth auth = new Auth();

    private Admin admin = new Admin();

    /**
     * What this node is called in every contact event it sends. Its host and
     * process when unset, which is a name nobody chose; a deployment of
     * several nodes names each.
     */
    private String nodeName;

    /** How large a heartbeat's statistics may be on this node, in bytes; 64 KB when unset. */
    private Integer heartbeatLimit;

    /**
     * Anything the runtime reads that this class has not grown a name for.
     * Left out of {@code toString} with the secrets: what it holds is not
     * known here, and a key passed this way would be printed with the rest.
     */
    @ToString.Exclude
    private Map<String, String> framework = new LinkedHashMap<>();

    /**
     * The framework properties this configuration amounts to.
     *
     * <p>Absent values are left out rather than written as empty strings: the
     * activators test for null, and "set to nothing" and "not set" are
     * different deployments — an authority configured with a blank key would
     * be an authority.
     */
    public Map<String, String> asFrameworkProperties() {
        Map<String, String> said = new LinkedHashMap<>(framework);
        put(said, "dbo.tenant.dir", tenants.getDirectory());
        put(said, "dbo.tenant.management.spec", managementSpec);
        put(said, "dbo.tenant.http.host", http.getHost());
        put(said, "dbo.tenant.http.port", http.getPort() == null
                ? null : String.valueOf(http.getPort()));
        put(said, "dbo.tenant.auth.kek", auth.getKek());
        put(said, "dbo.tenant.auth.issuer.base", auth.getIssuerBase());
        put(said, "dbo.tenant.auth.claims.max.bytes", auth.getClaimsMaxBytes() == null
                ? null : String.valueOf(auth.getClaimsMaxBytes()));
        put(said, "dbo.tenant.admin.url", admin.getJdbcUrl());
        put(said, "dbo.tenant.admin.user", admin.getUser());
        put(said, "dbo.tenant.admin.password", admin.getPassword());
        put(said, "dbo.substrate.url", substrate.getUrl());
        put(said, "dbo.substrate.user", substrate.getUser());
        put(said, "dbo.substrate.password", substrate.getPassword());
        put(said, "dbo.node.name", nodeName);
        put(said, "dbo.heartbeat.limit", heartbeatLimit == null
                ? null : String.valueOf(heartbeatLimit));
        if (mount == Mount.SERVLET) {
            // What makes the tenant activator wait for a server rather than
            // bind a port of its own. Said by the deployment rather than
            // discovered, because a tracker cannot tell a service that is
            // absent from one that has not arrived yet.
            said.put("dbo.tenant.http.shared", "true");
        }
        return said;
    }

    private static void put(Map<String, String> into, String name, String value) {
        if (value != null && !value.isBlank()) {
            into.put(name, value);
        }
    }

    /** Where the store's own doors answer. */
    public enum Mount {

        /**
         * On the application's port, through its servlet container.
         *
         * <p>One TLS configuration, one access log, one set of filters, and
         * {@code /t/{code}/fhir} reachable from the application's own tests.
         */
        SERVLET,

        /**
         * On a listener of the store's own, which is what the serving
         * distribution does. For an application with no web tier.
         */
        OWN_PORT
    }

    /** Where declarations are kept — not a list of tenants. */
    @Data
    public static class Tenants {
        private String directory;
    }

    /**
     * What the outside world reaches, which only the application knows.
     *
     * <p>Mounted in a servlet container the store binds nothing, and these
     * are still read: every self-link a surface writes and every issuer a
     * tenant mints is built from them. A deployment behind a proxy says the
     * address the proxy answers on.
     */
    @Data
    public static class Http {

        private String host = "127.0.0.1";

        private Integer port;
    }

    /** The authority every tenant's own is built on. */
    @Data
    public static class Auth {

        /**
         * Base64, 32 bytes. Without it this deployment refuses to serve.
         * Left out of {@code toString}, as every secret here is: whatever
         * prints these properties would print the key every tenant's own is
         * wrapped in.
         */
        @ToString.Exclude
        private String kek;

        private String issuerBase;

        /**
         * How large the claims about a person may grow, in bytes, before
         * minting them is refused. Unset, 4096.
         */
        private Integer claimsMaxBytes;

        /**
         * Serving tenants with no authority at all, said out loud.
         *
         * <p>The serving distribution exits rather than do this, and an
         * embedding that did it quietly would be a second artifact with a
         * different rule under one name. Embedded and test only.
         */
        private boolean disabled;

    }

    /**
     * The database this deployment provisions tenants in.
     *
     * <p>The store's own, not the application's {@code DataSource}: it keeps
     * a pool per tenant and writes under a transaction discipline its
     * promises rest on, and the application's transaction manager in that
     * path would be in the way of a single-transaction write.
     */
    /**
     * The deployment's own durable substrate, where it has one.
     *
     * <p>Each served tenant then opens a door on it beside its HTTP door, for
     * a participant inside the deployment that connects to the substrate and
     * to nothing else. Absent means this node serves lanes over HTTP and
     * in-process only, as every node did before the fleet — so it is left
     * unset rather than defaulted, because a URL guessed here would be a
     * second store nobody meant to reach.
     */
    @Data
    public static class Substrate {

        private String url;

        private String user;

        @ToString.Exclude
        private String password;

    }

    @Data
    public static class Admin {

        private String jdbcUrl;

        private String user;

        @ToString.Exclude
        private String password;
    }
}
