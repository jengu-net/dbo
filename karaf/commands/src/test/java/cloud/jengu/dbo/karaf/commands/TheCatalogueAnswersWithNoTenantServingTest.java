package cloud.jengu.dbo.karaf.commands;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue is what is installed, not what is running (#75).
 *
 * <p>The load-bearing claim of {@code dbo-process:list}: a node serving no
 * tenant at all still answers what it knows how to do. A command that quietly
 * needed a store would be useless in exactly the situation an operator opens
 * a console for — a node that is not serving.
 *
 * <p>No container and no store here on purpose. The registry is the only thing
 * this half reads, and a proxy answers it in a few lines; standing up Felix and
 * Postgres to prove a list would put a container test's cost on a claim that
 * does not need one.
 */
class TheCatalogueAnswersWithNoTenantServingTest {

    private static final StepDeclaration VALIDATE =
            StepDeclaration.of("lab.result.validate", "1.0", "r4")
                    .overridableBy("zone");

    @Test
    @DisplayName("a node with no tenant serving still lists what it knows, and says which "
            + "bundle contributed each step")
    void theCatalogueAnswersWithNothingServing() {
        String out = printed(() -> ProcessView.list(
                registryWith(Set.of(VALIDATE), "cloud.jengu.lab"), null, null));

        assertTrue(out.contains("lab.result.validate"), out);
        assertTrue(out.contains("cloud.jengu.lab"),
                "the provenance is the point: two modules declaring one id is a collision, "
                        + "so which one contributed it is the question — " + out);
        assertTrue(out.contains("zone"), "what may override it travels with it — " + out);
    }

    @Test
    @DisplayName("a node that has been contributed nothing says so, rather than printing an "
            + "empty table")
    void nothingDeclaredIsSaidInWords() {
        String out = printed(() -> ProcessView.list(registryWith(Set.of(), "none"), null, null));

        assertTrue(out.contains("No steps are declared here"), out);
        assertFalse(out.contains("overridable"),
                "an empty table reads as a broken command; a sentence reads as an answer — "
                        + out);
    }

    @Test
    @DisplayName("describing a step nobody contributed names it, and points at the list that "
            + "would have shown what does exist")
    void anUnknownStepIsRefusedByName() {
        String out = printed(() -> ProcessView.describe(
                registryWith(Set.of(VALIDATE), "cloud.jengu.lab"), null, "lab.result.sign",
                null, null));

        assertTrue(out.contains("lab.result.sign"), out);
        assertTrue(out.contains("dbo-process:list"),
                "\"not declared\" and \"declared elsewhere\" have different fixes — " + out);
    }

    @Test
    @DisplayName("a participant that published nothing says so, rather than reading as a "
            + "participant with nothing to report")
    void silenceIsNotZero() {
        // "(said nothing)" and "performed=0" are different facts about a
        // component, and a blank cell reads as the second.
        assertTrue(ProcessView.vitals(java.util.Map.of()).contains("said nothing"));
        assertTrue(ProcessView.vitals(null).contains("said nothing"));
    }

    @Test
    @DisplayName("vitals are rendered in whatever keys arrived, so a component kind nobody "
            + "has met yet is not quietly trimmed")
    void vitalsAreOpaque() {
        String rendered = ProcessView.vitals(new java.util.LinkedHashMap<>(java.util.Map.of(
                "queueDepth", "7")));

        assertTrue(rendered.contains("queueDepth=7"),
                "a fixed set of columns would drop a key nobody named — " + rendered);
    }

    /** What the console reads: service references, and the bundles behind them. */
    private static BundleContext registryWith(Set<StepDeclaration> steps, String bundleName) {
        Bundle bundle = (Bundle) Proxy.newProxyInstance(Bundle.class.getClassLoader(),
                new Class<?>[] {Bundle.class},
                (proxy, method, args) -> "getSymbolicName".equals(method.getName())
                        ? bundleName : defaultFor(method.getReturnType()));
        Steps.Catalogue catalogue = () -> steps;
        ServiceReference<?> reference = (ServiceReference<?>) Proxy.newProxyInstance(
                ServiceReference.class.getClassLoader(),
                new Class<?>[] {ServiceReference.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBundle" -> bundle;
                    case "getProperty" -> null;
                    default -> defaultFor(method.getReturnType());
                });
        return (BundleContext) Proxy.newProxyInstance(BundleContext.class.getClassLoader(),
                new Class<?>[] {BundleContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAllServiceReferences" ->
                            Steps.Catalogue.class.getName().equals(args[0]) && !steps.isEmpty()
                                    ? new ServiceReference<?>[] {reference} : null;
                    case "getService" -> catalogue;
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
