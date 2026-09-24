package cloud.jengu.dbo.spring.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What an application says instead of writing the wiring.
 *
 * <p>The guide's sample constructs an executor identity, a runner with two
 * durations, a step registration and a lane built from a base URI, a tenant
 * code, a participant name and a bearer supplier. Every one of those is
 * configuration rather than code, and this is where each of them went. What
 * is left for an application to write is the step.
 */
@ConfigurationProperties("dbo.worker")
public class DboWorkerProperties {

    /**
     * Who this worker is, to everything it does.
     *
     * <p>Required, and deliberately not defaulted to an artifact id: named,
     * versioned, provided and scoped is what makes an executor reproducible,
     * and an executor that cannot be reproduced cannot be held to what it
     * did.
     */
    private Identity identity = new Identity();

    /** How often the runner asks its lanes whether there is work. */
    private Duration poll = Duration.ofSeconds(2);

    /** How long a run is held before a later cycle may take it again. */
    private Duration hold = Duration.ofMinutes(1);

    /**
     * Whether the runner polls on its own.
     *
     * <p>False leaves it still, for an application testing its own step
     * services a cycle at a time. A runner that can be driven from outside
     * while it is also driving itself is two schedulers over one lane, so the
     * two are exclusive rather than both.
     */
    private boolean autoStart = true;

    /** How long a step still running at shutdown is waited for. */
    private Duration grace = Duration.ofSeconds(30);

    /** The tenants this worker performs work for. */
    private List<Lane> lanes = new ArrayList<>();

    public Identity getIdentity() {
        return identity;
    }

    public void setIdentity(Identity identity) {
        this.identity = identity;
    }

    public Duration getPoll() {
        return poll;
    }

    public void setPoll(Duration poll) {
        this.poll = poll;
    }

    public Duration getHold() {
        return hold;
    }

    public void setHold(Duration hold) {
        this.hold = hold;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public Duration getGrace() {
        return grace;
    }

    public void setGrace(Duration grace) {
        this.grace = grace;
    }

    public List<Lane> getLanes() {
        return lanes;
    }

    public void setLanes(List<Lane> lanes) {
        this.lanes = lanes;
    }

    /** Named and versioned, because a run records who performed it. */
    public static class Identity {

        private String name;

        private String version = "1";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    /** One tenant this worker is offered work by. */
    public static class Lane {

        /** The tenant's code, as the deployment declared it. */
        private String tenant;

        /** Where that tenant answers, for example {@code https://host/t/code/}. */
        private URI base;

        /** A client this tenant holds, and its secret. */
        private Token token = new Token();

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public URI getBase() {
            return base;
        }

        public void setBase(URI base) {
            this.base = base;
        }

        public Token getToken() {
            return token;
        }

        public void setToken(Token token) {
            this.token = token;
        }
    }

    /**
     * How this worker comes to be carrying a credential.
     *
     * <p>A runner outlives an access token, and one captured at construction
     * starts failing an hour later in a way that reads like the store going
     * away. So the ordinary case is a client and a secret, which this signs
     * in with and signs in again with before the token expires.
     */
    public static class Token {

        private String clientId;

        private String clientSecret;

        /**
         * A token somebody else obtained, used as it stands.
         *
         * <p>For a spike, and it says so. A process that runs longer than the
         * token does starts failing at whatever hour it was started plus one.
         */
        private String value;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
