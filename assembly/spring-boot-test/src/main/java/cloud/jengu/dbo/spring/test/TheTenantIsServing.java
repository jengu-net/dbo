package cloud.jengu.dbo.spring.test;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.spring.server.DboTenants;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.util.List;

/**
 * Waits for the world to be serving, and issues the credential a worker needs.
 *
 * <p>Two things can only happen after the context is up, which is why they are
 * here rather than in the initializer. A tenant has to have come up before
 * anything can ask it for anything — a face is expanded the first time, and
 * that is most of a minute — and a client can only be registered by the
 * authority of a tenant that exists.
 *
 * <p><b>Then the worker is started, and not before.</b> A runner polling a
 * lane whose credential has not been issued fails every cycle and says so in a
 * log nobody is reading, which is the shape of a test that passes for the
 * wrong reason later.
 */
public final class TheTenantIsServing implements BeforeAllCallback {

    /** The client a test's records calls carry. Stable, so a rerun reuses it. */
    static final String CLIENT = "dbo-test-worker";

    /**
     * The client the LANE carries, which is a different one.
     *
     * <p>A lane polls the step surface, and that surface admits a credential
     * that may act in work — not the broad one. Wiring a lane with the records
     * client is how a runner comes up, polls, is refused every cycle, and says
     * so in a log nobody is reading while a test waits for work that will
     * never be taken.
     */
    static final String WORK_CLIENT = CLIENT + "-work";

    static final String SECRET = "a-secret-for-" + CLIENT;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        ApplicationContext application = SpringExtension.getApplicationContext(context);
        DboTenants tenants = application.getBean(DboTenants.class);

        String tenant = application.getEnvironment().getProperty(DboTestProperties.LANE_TENANT);
        if (tenant == null || tenant.isBlank()) {
            // Nothing in this context performs work, so there is nothing to
            // wait for beyond the context itself.
            return;
        }

        untilServing(tenants, tenant);
        TenantAuthority authority = tenants.authority(tenant).orElseThrow(
                () -> new IllegalStateException(tenant + " is serving and has no authority, so "
                        + "no credential can be issued for a worker to carry"));
        authority.ensureClient(CLIENT, SECRET, scopes(application));
        if (overTheSubstrate(application)) {
            // ITS OWN CLIENT, not the one a token is carried on. An enrolled
            // participant and a client that signs in are different things, and
            // a record made once without keys is not given them later — so
            // sharing an id with the HTTP tests in this JVM would leave the
            // enrolment silently absent and every ask refused on its
            // signature.
            // The PUBLIC halves, against the participant the worker carries the
            // private ones for. The plane between holds no token, so what
            // admits an ask there is a signature this record can check — and a
            // participant with no record on the tenant is not late, it is
            // unknown.
            authority.ensureClient(ENROLLED, SECRET,
                    List.of(cloud.jengu.dbo.auth.Scopes.WORK),
                    cloud.jengu.dbo.core.api.seal.ParticipantKey.of(sealingOfThisJvm.getPublic()),
                    cloud.jengu.dbo.core.api.seal.SigningKey.of(signingOfThisJvm.getPublic()));
        } else {
            authority.ensureClient(WORK_CLIENT, SECRET,
                    List.of(cloud.jengu.dbo.auth.Scopes.WORK));
        }
        startTheWorker(application);
    }

    /**
     * A liveness wait rather than a budget: how long a tenant takes to come up
     * is a fact about the machine, and a shorter number here would only fail
     * on the slow ones.
     */
    private static void untilServing(DboTenants tenants, String tenant) throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(8).toNanos();
        while (System.nanoTime() < giveUp) {
            if (tenants.serving().contains(tenant)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(tenant + " never came up, so this test has no tenant to "
                + "be about. It is declared in the world at "
                + DboTestProperties.WORLD + ", and a tenant that is declared and not serving has "
                + "said why in the log above this line.");
    }

    /**
     * The pair this JVM enrols with, made once.
     *
     * <p>Once per JVM rather than per context for the reason the key is: the
     * enrolment lives on a tenant in a database that outlives a context, so a
     * second context minting a second pair would hold private halves the
     * tenant has never heard of.
     */
    /** The participant a lane on the substrate is held by, enrolled with keys. */
    static final String ENROLLED = "dbo-test-enrolled-participant";

    private static final java.security.KeyPair sealingOfThisJvm =
            cloud.jengu.dbo.core.api.seal.KeyWrap.newParticipantKeyPair();

    private static final java.security.KeyPair signingOfThisJvm =
            cloud.jengu.dbo.core.api.seal.SigningKey.newKeyPair();

    /** The private half this worker is sealed to, as the container takes it. */
    static String sealingKeyOfThisJvm() {
        return java.util.Base64.getEncoder()
                .encodeToString(sealingOfThisJvm.getPrivate().getEncoded());
    }

    /** The private half it signs its asks with. */
    static String signingKeyOfThisJvm() {
        return java.util.Base64.getEncoder()
                .encodeToString(signingOfThisJvm.getPrivate().getEncoded());
    }

    private static boolean overTheSubstrate(ApplicationContext application) {
        return "substrate".equalsIgnoreCase(application.getEnvironment()
                .getProperty(DboTestProperties.LANE_CARRIER, "http"));
    }

    private static List<String> scopes(ApplicationContext application) {
        String listed = application.getEnvironment().getProperty(DboTestProperties.LANE_SCOPES);
        return listed == null || listed.isBlank()
                ? List.of("system/*.read", "system/*.write")
                : List.of(listed.split("\\s*,\\s*"));
    }

    /**
     * Started by name, because nothing else can: the worker is left still by
     * the initializer so that it does not poll a lane before there is a
     * credential for it.
     */
    private static void startTheWorker(ApplicationContext application) {
        application.getBeansOfType(org.springframework.context.SmartLifecycle.class).values()
                .stream()
                .filter(bean -> bean.getClass().getName()
                        .equals("cloud.jengu.dbo.spring.worker.DboWorker"))
                .forEach(org.springframework.context.SmartLifecycle::start);
    }
}
