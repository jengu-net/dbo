package cloud.jengu.dbo.spring.server;

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

    /** Anything the runtime reads that this class has not grown a name for. */
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
        put(said, "dbo.tenant.admin.url", admin.getJdbcUrl());
        put(said, "dbo.tenant.admin.user", admin.getUser());
        put(said, "dbo.tenant.admin.password", admin.getPassword());
        put(said, "dbo.substrate.url", substrate.getUrl());
        put(said, "dbo.substrate.user", substrate.getUser());
        put(said, "dbo.substrate.password", substrate.getPassword());
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

    public Mount getMount() {
        return mount;
    }

    public void setMount(Mount mount) {
        this.mount = mount;
    }

    public String getManagementSpec() {
        return managementSpec;
    }

    public void setManagementSpec(String managementSpec) {
        this.managementSpec = managementSpec;
    }

    public Tenants getTenants() {
        return tenants;
    }

    public void setTenants(Tenants tenants) {
        this.tenants = tenants;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public Substrate getSubstrate() {
        return substrate;
    }

    public void setSubstrate(Substrate substrate) {
        this.substrate = substrate;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Map<String, String> getFramework() {
        return framework;
    }

    public void setFramework(Map<String, String> framework) {
        this.framework = framework;
    }

    /** Where declarations are kept — not a list of tenants. */
    public static class Tenants {

        private String directory;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

    /**
     * What the outside world reaches, which only the application knows.
     *
     * <p>Mounted in a servlet container the store binds nothing, and these
     * are still read: every self-link a surface writes and every issuer a
     * tenant mints is built from them. A deployment behind a proxy says the
     * address the proxy answers on.
     */
    public static class Http {

        private String host = "127.0.0.1";

        private Integer port;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }
    }

    /** The authority every tenant's own is built on. */
    public static class Auth {

        /** Base64, 32 bytes. Without it this deployment refuses to serve. */
        private String kek;

        private String issuerBase;

        /**
         * Serving tenants with no authority at all, said out loud.
         *
         * <p>The serving distribution exits rather than do this, and an
         * embedding that did it quietly would be a second artifact with a
         * different rule under one name. Embedded and test only.
         */
        private boolean disabled;

        public String getKek() {
            return kek;
        }

        public void setKek(String kek) {
            this.kek = kek;
        }

        public String getIssuerBase() {
            return issuerBase;
        }

        public void setIssuerBase(String issuerBase) {
            this.issuerBase = issuerBase;
        }

        public boolean isDisabled() {
            return disabled;
        }

        public void setDisabled(boolean disabled) {
            this.disabled = disabled;
        }
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
    public static class Substrate {

        private String url;

        private String user;

        private String password;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Admin {

        private String jdbcUrl;

        private String user;

        private String password;

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
