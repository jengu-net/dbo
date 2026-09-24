package cloud.jengu.dbo.spring.worker;

import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.ProvingLane;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.embedded.DboRegistrar;
import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean implements one interface, and the application performs a step.
 *
 * <p>This is the assembly's claim, whole. The sample's {@code AdmitStep} is
 * already the shape an application writes — it implements
 * {@code StepService}, declares a code, reads its inputs and answers — and
 * its comment says <i>this is the whole of what an integrator writes</i>.
 * That was not quite true: an integrator also wrote {@code Admissions},
 * forty lines constructing an executor, a runner, a registration and a lane.
 * What is asserted here is that adding this jar makes the comment true.
 *
 * <p><b>Nothing in this test constructs a runner, registers a step service or
 * attaches a lane.</b> A bean and some configuration go in; if the step is
 * performed, the only thing that can have wired it is the container's own
 * whiteboard — the same shape of proof the driver-bundle test makes from the
 * other side, and the reason that one exists rather than a tenth test that
 * builds a runner itself.
 *
 * <p>No store, no tenant, no network. The lane is in-process, which is what
 * lets the whole claim be made with nothing to stand up.
 */
class ABeanIsAStepThisApplicationPerformsIT {

    private static final String STEP = "spring.worker.report";

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DboWorkerAutoConfiguration.class))
            .withPropertyValues("dbo.worker.identity.name=a-spring-worker",
                    // Fast enough that this test is not mostly waiting, slow
                    // enough to be a poll rather than a busy loop.
                    "dbo.worker.poll=50ms");

    @Test
    @DisplayName("a bean that implements a step service has its work performed, with no runner "
            + "constructed, no service registered by hand and no lane attached")
    void aBeanIsAStep() {
        application.withUserConfiguration(AnApplication.class).run(context -> {
            AnApplication.Report report = context.getBean(AnApplication.Report.class);

            assertTrue(report.performed.await(20, TimeUnit.SECONDS),
                    "the step never ran, so a bean implementing " + StepService.class.getName()
                            + " reaches nothing and this assembly is a jar that starts a "
                            + "container and performs no work");
            // A run names its process and its step apart — the full code is
            // <module>.<process>.<step> — so what arrives here is the leaf.
            // Asserted rather than glossed over, because a test that read the
            // whole code back from the run would be asserting a shape the
            // store does not have.
            assertEquals(STEP.substring(STEP.lastIndexOf('.') + 1), report.ran.get(),
                    "something ran, and not the step this application offered");

            DboWorker worker = context.getBean(DboWorker.class);
            assertEquals(Map.of(STEP, AnApplication.Report.class.getName()), worker.performing(),
                    "the worker does not say it performs the bean's step, so an application "
                            + "asking whether its bean was taken up is told the wrong thing");
        });
    }

    @Test
    @DisplayName("two beans declaring one step code are refused at context refresh, naming "
            + "both, rather than one of them silently never running")
    void twoBeansForOneStepAreRefused() {
        application.withUserConfiguration(TwoOfThem.class).run(context -> {
            assertNotNull(context.getStartupFailure(),
                    "the context started with two beans performing one step. The runner keys a "
                            + "step by its code, so one of them never runs and its author finds "
                            + "out by watching which class logged");
            String said = rootOf(context.getStartupFailure()).getMessage();
            assertTrue(said.contains(STEP) && said.contains("One") && said.contains("Other"),
                    "the refusal does not name the step and both beans, so whoever reads it "
                            + "still has to go and find them: " + said);
        });
    }

    @Test
    @DisplayName("a lane with no way to obtain a credential is refused at context refresh, "
            + "because it could never be offered work and will not converge on its own")
    void aLaneThatCouldNeverBeUsedIsRefused() {
        application.withUserConfiguration(AnApplication.class)
                .withPropertyValues("dbo.worker.lanes[0].tenant=hogwarts",
                        "dbo.worker.lanes[0].base=https://deployment.example/t/hogwarts/")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "a lane with no client, no secret and no token started anyway, so "
                                    + "this worker would poll a tenant that refuses it for as "
                                    + "long as it runs");
                    assertTrue(rootOf(context.getStartupFailure()).getMessage()
                                    .contains("hogwarts"),
                            "the refusal does not name the lane: "
                                    + rootOf(context.getStartupFailure()).getMessage());
                });
    }

    private static Throwable rootOf(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * An application: one bean that is a step, and somewhere it is offered
     * work from.
     *
     * <p>The lane is registered here rather than configured, because what is
     * under test is the whiteboard and not the HTTP carrier. A configured
     * lane would need a tenant to answer it, and the claim — a bean is a step
     * — has nothing to do with where the work came from.
     */
    @Configuration
    static class AnApplication {

        @Bean
        Report aStepThisApplicationPerforms() {
            return new Report();
        }

        /**
         * The same standing lane the driver-bundle probe offers, for the same
         * reason: a runner that saw the service and had nowhere to poll would
         * sit quietly, and the test would read the same silence it reads when
         * the whiteboard is broken.
         */
        @Bean(destroyMethod = "close")
        DboRegistrar.Registration somewhereToBeOfferedWork(EmbeddedRuntime runtime) {
            return runtime.registrar().register(Lane.class,
                    ProvingLane.offering(STEP).with("specimen", "Basic", "{\"n\":1}").lane(),
                    Map.of());
        }

        /** The whole of what an application writes. */
        static class Report implements StepService {

            final CountDownLatch performed = new CountDownLatch(1);
            final AtomicReference<String> ran = new AtomicReference<>();

            @Override
            public String step() {
                return STEP;
            }

            @Override
            public Outcome perform(Work work) {
                work.progress().milestone("read", Map.of("specimens", 1L));
                ran.set(work.run().step());
                performed.countDown();
                return Outcome.done(Map.of("reported", 1L));
            }
        }
    }

    /** Two beans, one step code. */
    @Configuration
    static class TwoOfThem {

        @Bean
        StepService one() {
            return new One();
        }

        @Bean
        StepService other() {
            return new Other();
        }

        static class One implements StepService {

            @Override
            public String step() {
                return STEP;
            }

            @Override
            public Outcome perform(Work work) {
                return Outcome.done(Map.of());
            }
        }

        static class Other implements StepService {

            @Override
            public String step() {
                return STEP;
            }

            @Override
            public Outcome perform(Work work) {
                return Outcome.done(Map.of());
            }
        }
    }
}
