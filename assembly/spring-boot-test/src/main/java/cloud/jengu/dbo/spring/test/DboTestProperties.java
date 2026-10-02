package cloud.jengu.dbo.spring.test;

import java.util.List;

/**
 * What a test says it needs. Nothing here is what the application reads.
 *
 * <p>The application's own configuration — where its database is, which world
 * it serves, where a lane answers, the credential it carries — is DERIVED from
 * this and contributed as a property source. That separation is the whole
 * design: a test that named {@code dbo.admin.jdbc-url} would be describing a
 * deployment, and the deployment under test is the application's own.
 *
 * <p>Read from the environment rather than bound as a bean, because it is
 * needed before there is a context to hold one: the database has to exist
 * before the application can be told where it is.
 */
public final class DboTestProperties {

    /** The database image this JVM runs, once, for every test in it. */
    public static final String IMAGE = "dbo.test.image";

    /** The directory of tenant specs the application under test serves. */
    public static final String WORLD = "dbo.test.world";

    /** The tenant a worker in this context performs for, if there is one. */
    public static final String LANE_TENANT = "dbo.test.lane.tenant";

    /** What the credential that worker carries is allowed to do. */
    public static final String LANE_SCOPES = "dbo.test.lane.scopes";

    /**
     * How the lane is carried: {@code http} by default, or {@code substrate}.
     *
     * <p>A test says which because the two are different claims. Over HTTP a
     * participant is somebody else's application reaching this deployment
     * through a port; over the substrate it is part of the deployment, reading
     * the database the serving side already runs on. Everything that differs
     * — a base and a credential against an enrolment and a substrate URL —
     * is derived from this one word.
     */
    public static final String LANE_CARRIER = "dbo.test.lane.carrier";

    /**
     * Whether the deployment has a substrate, for lanes the application itself
     * names rather than one this test derives.
     *
     * <p>{@code true} gives the serving half a substrate and the worker a
     * participant on it, holding private halves minted once for this JVM. What
     * the application then needs in order to enrol that participant on a
     * tenant — its name and the PUBLIC halves — is readable as
     * {@link #SUBSTRATE_PARTICIPANT}, {@link #SUBSTRATE_SEALING_KEY} and
     * {@link #SUBSTRATE_SIGNING_KEY}, so it enrols it the way it would enrol
     * any worker it was told about. Which lanes go over the substrate stays
     * the application's own configuration: a lane naming no base.
     */
    public static final String SUBSTRATE = "dbo.test.substrate";

    /** The participant the worker holds its substrate lanes as. Derived. */
    public static final String SUBSTRATE_PARTICIPANT = "dbo.test.substrate.participant";

    /** The public half it is sealed to, as a JWK. Derived. */
    public static final String SUBSTRATE_SEALING_KEY = "dbo.test.substrate.sealing-key";

    /** The public half it signs with, as a JWK. Derived. */
    public static final String SUBSTRATE_SIGNING_KEY = "dbo.test.substrate.signing-key";

    private static final String DEFAULT_IMAGE = "postgres:17-alpine";

    private static final List<String> DEFAULT_SCOPES =
            List.of("system/*.read", "system/*.write");

    private final String image;
    private final String world;
    private final String laneTenant;

    private final boolean laneOverTheSubstrate;
    private final List<String> laneScopes;

    DboTestProperties(String image, String world, String laneTenant, List<String> laneScopes,
            boolean laneOverTheSubstrate) {
        this.laneOverTheSubstrate = laneOverTheSubstrate;
        this.image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
        this.world = world;
        this.laneTenant = laneTenant;
        this.laneScopes = laneScopes == null || laneScopes.isEmpty() ? DEFAULT_SCOPES : laneScopes;
    }

    public String image() {
        return image;
    }

    /**
     * Where the world is, and a test without one is refused rather than served
     * an empty deployment: a context that comes up holding no tenant answers
     * every question with 404, which reads exactly like a test that is wrong
     * about its paths.
     */
    public String world() {
        if (world == null || world.isBlank()) {
            throw new IllegalStateException(WORLD + " is not set, so this test has no world to "
                    + "serve. A context with no tenants comes up perfectly and answers nothing, "
                    + "which is indistinguishable from a test that is wrong about its paths.");
        }
        return world;
    }

    /** Null where nothing in this context performs work. */
    /** Whether the lane this test declares is carried by the substrate. */
    public boolean laneOverTheSubstrate() {
        return laneOverTheSubstrate;
    }

    public String laneTenant() {
        return laneTenant == null || laneTenant.isBlank() ? null : laneTenant;
    }

    public List<String> laneScopes() {
        return laneScopes;
    }
}
