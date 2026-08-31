package cloud.jengu.dbo.karaf.commands;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.harness.SharedPostgres;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console says who would run a step, and it is the store's answer (#75).
 *
 * <p>The catalogue half of these commands is proven without a store, because
 * it needs none. This is the other half, and it is the half an operator opens
 * a console for: <em>which executor would take this step here, now</em>. It
 * cannot be answered without a tenant's declarations, so it cannot be proven
 * without a store — which is why it sat untested while the commands themselves
 * were built.
 *
 * <p>The claim is narrow and worth stating exactly: the console does not
 * reimplement resolution, it <b>asks</b> it. Resolution's own rules are proven
 * in {@code ExecutorResolutionTest} against the primitive; what is proven here
 * is that what the console prints is what the store would do — a console that
 * described a different answer than the one the tenant will actually take
 * would be worse than no console.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheConsoleSaysWhoWouldRunAStepIT {

    private static final String TENANT = "consoletenant";
    private static final String PROCESS = "lab.result";
    private static final String STEP = "validate";
    private static final String ID = PROCESS + "." + STEP;

    /** Open to a zone overriding it — the grant is the step's to give. */
    private static final StepDeclaration OVERRIDABLE =
            StepDeclaration.of(ID, "1.0", "r4").overridableBy("zone");
    /** The same step, granting nothing: precedence selects, the step admits. */
    private static final StepDeclaration SEALED =
            StepDeclaration.of("lab.result.sign", "1.0", "r4");

    static ObjectStore store;
    static ChangeFeed feed;
    static Declarations declarations;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("TheConsoleSaysWhoWouldRunAStepIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, WorkModel.registrations());
        feed = new PgChangeFeed(ds, WorkModel.DOMAIN);
        declarations = new Declarations(store, feed, Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("with a zone candidate declared and the work happening in that zone, the "
            + "console names the zone's executor — the most local one the step admits")
    @Proving({DboPromises.PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC,
            DboPromises.PROC_EXECUTOR_DECLARES_ITSELF})
    void theMostLocalCandidateIsNamed() {
        declare(STEP, "national", Scope.BASELINE);
        declare(STEP, "the-zones-own", Scope.zone("ee"));

        String out = printed(() -> ProcessView.describe(
                registry(Set.of(OVERRIDABLE)), TENANT, ID, "ee", null));

        assertTrue(out.contains("would run here: the-zones-own"),
                "the console asks resolution rather than describing it — " + out);
        assertTrue(out.contains("national"),
                "and both candidates are still shown: who could have taken it is the "
                        + "context for who did — " + out);
    }

    @Test
    @DisplayName("a step that grants nobody the right to override keeps its own executor, "
            + "and the console says a candidate was refused on the way")
    @Proving(DboPromises.PROC_A_STEP_GRANTS_THE_RIGHT_TO_OVERRIDE)
    void aRefusedOverrideIsSaidOutLoud() {
        declare("sign", "national", Scope.BASELINE);
        declare("sign", "the-zones-own", Scope.zone("ee"));

        String out = printed(() -> ProcessView.describe(
                registry(Set.of(SEALED)), TENANT, "lab.result.sign", "ee", null));

        assertTrue(out.contains("would run here: national"),
                "precedence selects, but the step admits — " + out);
        assertTrue(out.contains("refused on the way"),
                "a refused override is a fact about somebody's rule, and an operator "
                        + "wondering why the local one did not take it needs it — " + out);
    }

    @Test
    @DisplayName("a step nothing declares is held by a person, said in words rather than "
            + "shown as an empty table")
    @Proving(DboPromises.PROC_FALL_THROUGH_IS_COUNTABLE)
    void nothingDeclaredIsAPersonHoldingIt() {
        String out = printed(() -> ProcessView.describe(
                registry(Set.of(StepDeclaration.of("lab.result.report", "1.0", "r4"))),
                TENANT, "lab.result.report", null, null));

        assertTrue(out.contains("held by a person"),
                "manual is the baseline rather than an absence — " + out);
        assertFalse(out.contains("would run here: the-zones-own"), out);
    }

    private static void declare(String step, String name, Scope scope) {
        declarations.declare(new Declarations.Declared(PROCESS, step, name, "1.0",
                "the.hospital", scope, name + "-consumer"));
    }

    /**
     * What the console reads: the installed catalogue, and one tenant's store
     * and feed under the tenant property it filters on.
     */
    private static BundleContext registry(Set<StepDeclaration> steps) {
        Bundle bundle = (Bundle) Proxy.newProxyInstance(Bundle.class.getClassLoader(),
                new Class<?>[] {Bundle.class},
                (proxy, method, args) -> "getSymbolicName".equals(method.getName())
                        ? "cloud.jengu.lab" : defaultFor(method.getReturnType()));
        Steps.Catalogue catalogue = () -> steps;
        Map<String, Object> services = Map.of(
                Steps.Catalogue.class.getName(), catalogue,
                ObjectStore.class.getName(), store,
                ChangeFeed.class.getName(), feed);
        Map<String, ServiceReference<?>> references = new java.util.LinkedHashMap<>();
        // Identity-keyed, so getService answers with the service that
        // reference stands for rather than with whichever came first: the
        // console asks for three different services through one registry, and
        // a proxy that conflated them would prove nothing about the wiring.
        Map<ServiceReference<?>, Object> behind = new java.util.IdentityHashMap<>();
        services.forEach((name, service) -> {
            ServiceReference<?> reference = (ServiceReference<?>) Proxy.newProxyInstance(
                    ServiceReference.class.getClassLoader(),
                    new Class<?>[] {ServiceReference.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBundle" -> bundle;
                        // The console finds a tenant's store by this property
                        // and by nothing else; a reference without it is
                        // another node's business.
                        case "getProperty" -> Tenants.TENANT_PROPERTY.equals(args[0])
                                ? TENANT : null;
                        default -> defaultFor(method.getReturnType());
                    });
            references.put(name, reference);
            behind.put(reference, service);
        });
        return (BundleContext) Proxy.newProxyInstance(BundleContext.class.getClassLoader(),
                new Class<?>[] {BundleContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAllServiceReferences" ->
                            references.containsKey(String.valueOf(args[0]))
                                    ? new ServiceReference<?>[] {
                                            references.get(String.valueOf(args[0]))}
                                    : null;
                    case "getService" -> behind.get(args[0]);
                    default -> defaultFor(method.getReturnType());
                });
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        return boolean.class.equals(type) ? Boolean.FALSE : (Object) 0;
    }

    private static String printed(Runnable body) {
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(out);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
