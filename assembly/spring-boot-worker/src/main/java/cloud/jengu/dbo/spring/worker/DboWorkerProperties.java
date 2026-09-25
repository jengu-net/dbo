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

    /** The deployment's own substrate, where any lane is carried by it. */
    private Substrate substrate = new Substrate();

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
    public Substrate getSubstrate() {
        return substrate;
    }

    public void setSubstrate(Substrate substrate) {
        this.substrate = substrate;
    }

    /** Whether any lane is carried by the substrate rather than over HTTP. */
    public boolean anyLaneOverTheSubstrate() {
        return lanes.stream().anyMatch(Lane::overTheSubstrate);
    }

    /** The tenants whose lanes the substrate carries, as the container names them. */
    public String tenantsOverTheSubstrate() {
        return lanes.stream().filter(Lane::overTheSubstrate)
                .map(Lane::getTenant).collect(java.util.stream.Collectors.joining(","));
    }

    public static class Identity {

        private String name;

        private String version = "1";

        /** The baseline, because it is the only scope every step admits. */
        private Scope scope = Scope.BASELINE;

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

        public Scope getScope() {
            return scope;
        }

        public void setScope(Scope scope) {
            this.scope = scope;
        }
    }

    /**
     * What an executor is declaring itself to be for the steps it performs.
     *
     * <p>The baseline always may perform a step — it is not an override, it is
     * the rule — while anything more local may only where the step said so, and
     * a step is not overridable by default. So this defaults to the baseline
     * and an application says otherwise when it means to.
     */
    /**
     * The deployment's own substrate, and what this worker enrolled as.
     *
     * <p>Needed only where a lane is carried by the substrate. The keys are the
     * PRIVATE halves, base64 PKCS#8, of a pair whose public halves the tenant
     * holds against this participant's client record: the plane between carries
     * no token, so an ask is signed rather than presented, and a payload is
     * sealed to the participant rather than handed over in the clear.
     */
    public static class Substrate {

        /** Where the deployment's own durable layer lives. */
        private String url;

        private String user;

        private String password;

        /** The name this worker enrolled under, which is its cursor on each feed. */
        private String participant;

        /** Whose code this is: a provider can be withdrawn, so a run names it. */
        private String provider;

        /** The private half of the key this participant is sealed to. */
        private String sealingKey;

        /** The private half of the key it signs its asks with. */
        private String signingKey;

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

        public String getParticipant() {
            return participant;
        }

        public void setParticipant(String participant) {
            this.participant = participant;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getSealingKey() {
            return sealingKey;
        }

        public void setSealingKey(String sealingKey) {
            this.sealingKey = sealingKey;
        }

        public String getSigningKey() {
            return signingKey;
        }

        public void setSigningKey(String signingKey) {
            this.signingKey = signingKey;
        }
    }

    public enum Scope {

        /**
         * The rule for the step, which is what a participant performing one is.
         * A service that brings its own declaration must be this: bringing a
         * step is not varying somebody else's.
         */
        BASELINE,

        /**
         * A local variation for this tenant, which only a step that opened
         * itself to being overridden will admit.
         */
        ORGANISATION
    }

    /** One tenant this worker is offered work by. */
    public static class Lane {

        /** The tenant's code, as the deployment declared it. */
        private String tenant;

        /**
         * Where that tenant answers, for example {@code https://host/t/code/}.
         *
         * <p>Absent means this lane is carried by the deployment's substrate
         * rather than over HTTP — see {@link #overTheSubstrate()}.
         */
        private URI base;

        /** A client this tenant holds, and its secret. */
        private Token token = new Token();

        /**
         * Whether this lane is carried by the substrate rather than by HTTP.
         *
         * <p><b>Inferred from what the lane was given rather than named.</b> A
         * base and a credential is a lane into somebody else's deployment,
         * reached over a port; neither is a lane into the store this
         * application is part of, reached over the database it already runs on.
         * A property saying which would be a third thing to keep consistent
         * with the two that already decide it.
         */
        public boolean overTheSubstrate() {
            return base == null;
        }

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
