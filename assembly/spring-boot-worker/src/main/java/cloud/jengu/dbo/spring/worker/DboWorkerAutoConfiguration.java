package cloud.jengu.dbo.spring.worker;

import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.embedded.FrameworkContribution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Adding this jar is the whole deployment.
 *
 * <p>A bean that implements {@code StepService} becomes a step this
 * application performs. The lanes it is offered work over come from
 * configuration. There is no store here and no way to get one — what this
 * carries is the work vocabulary and the lane, which is the whole of what a
 * party outside the deployment compiles against.
 */
@AutoConfiguration
@EnableConfigurationProperties(DboWorkerProperties.class)
public class DboWorkerAutoConfiguration {

    /**
     * The container, booted with the runner's own dials.
     *
     * <p>The two durations are framework properties rather than constructor
     * arguments because the runner reads them through the container, which is
     * where a driver bundle would set them too. An embedding is not a special
     * mode.
     *
     * <p><b>Booted as the bean is created, not on a lifecycle.</b> Anything
     * that takes this bean takes a RUNNING container — including a bean of
     * the application's own that puts something on the whiteboard while it is
     * being constructed, which is early in any lifecycle and impossible to
     * order against one. The alternative was handing out a runtime that
     * refuses every call until some later phase, which is a bean that is only
     * sometimes what it says it is.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public EmbeddedRuntime dboEmbeddedRuntime(
            ObjectProvider<FrameworkContribution> contributions) {
        // EVERY contribution, not this configuration's own. An application
        // that also serves tenants carries a second one, and the container it
        // reaches is this one — whichever half of the host happened to build
        // it.
        return new EmbeddedRuntime(getClass().getClassLoader(),
                FrameworkContribution.merged(contributions.orderedStream().toList()),
                EmbeddedRuntime.storageUnder(
                        Path.of(System.getProperty("java.io.tmpdir")), "dbo-embedded"));
    }

    /** What performing work tells the container. */
    @Bean
    public FrameworkContribution dboWorkerFrameworkContribution(DboWorkerProperties properties) {
        return () -> {
            Map<String, String> framework = new LinkedHashMap<>();
            framework.put("dbo.runner.poll.millis",
                    String.valueOf(properties.getPoll().toMillis()));
            framework.put("dbo.runner.hold.millis",
                    String.valueOf(properties.getHold().toMillis()));
            return framework;
        };
    }

    /**
     * The worker: the application's steps and its configured lanes, put where
     * the container's whiteboard looks.
     *
     * <p>Every refusal this configuration makes is made HERE, at context
     * refresh, and not later. The runtime's own whiteboards refuse a
     * registration missing what it needs by name, and that refusal reaches a
     * log nobody is reading at four in the morning. Spring knows every bean
     * at refresh and can tell the author instead.
     */
    @Bean
    @ConditionalOnMissingBean
    public DboWorker dboWorker(EmbeddedRuntime runtime, DboWorkerProperties properties,
            ObjectProvider<StepService> steps, ObjectProvider<TenantToken> tokens) {
        List<StepService> performing = steps.orderedStream().toList();
        refuseADuplicateStep(performing);
        refuseALaneThatCannotBeUsed(properties);
        refuseAnOverrideOfAStepThisApplicationBrought(performing, properties);
        if (performing.isEmpty()) {
            // Not a refusal: a worker with no steps yet is an application
            // part-way through being written, and failing its context would
            // be this starter having an opinion about somebody's Tuesday.
            // Said once, because the alternative is silence that looks
            // exactly like working.
            org.slf4j.LoggerFactory.getLogger("dbo.worker").warn(
                    "no bean implements {}, so this application performs no steps",
                    StepService.class.getName());
        }
        Map<String, Supplier<String>> supplied = new LinkedHashMap<>();
        tokens.forEach(token -> supplied.put(token.tenant(), token));
        return new DboWorker(runtime, properties, performing,
                DboWorker.tokensFor(properties, supplied));
    }

    /**
     * Two beans for one step code, refused with both names.
     *
     * <p>The runner keys a registration by the step's code, so a second one
     * is a last-one-wins an application author would have to discover by
     * watching which of their two classes ran. Spring sees both at refresh.
     */
    private static void refuseADuplicateStep(List<StepService> performing) {
        Map<String, List<String>> byStep = new TreeMap<>();
        for (StepService step : performing) {
            byStep.computeIfAbsent(step.step(), code -> new ArrayList<>())
                    .add(step.getClass().getName());
        }
        List<String> twice = byStep.entrySet().stream()
                .filter(each -> each.getValue().size() > 1)
                .map(each -> each.getKey() + " is declared by " + each.getValue())
                .toList();
        if (!twice.isEmpty()) {
            throw new IllegalStateException("two beans perform one step, and the runner keys a "
                    + "step by its code — so one of them would silently never run: "
                    + String.join("; ", twice));
        }
    }

    /**
     * A lane the configuration got wrong, refused; a lane that is merely not
     * answering, allowed.
     *
     * <p>The distinction is the whole of this starter's posture about
     * startup. A worker exists to be up when its tenant is up, and a
     * deployment that restarts them in the wrong order should converge rather
     * than crash-loop — so an unreachable tenant is not a failure to start,
     * and the poll loop that was going to run anyway is the retry. What does
     * not converge is a lane with no tenant code, no address, or no way to
     * obtain a credential, and those are refused where the author can see it.
     */
    /**
     * A step this application brought is not one it varies.
     *
     * <p>The baseline is the rule for a step and always admitted; anything more
     * local is an override, which a step admits only where it said so, and not
     * overridable is the default. So an application that carries its own
     * declaration and asks to act at an organisation has said two things that
     * cannot both be true — and the store would tell it so one claim at a time,
     * on a run that a feed cursor has already moved past.
     *
     * <p>Said here instead, once, with the step named, because the author is
     * looking at the configuration now and will not be looking at a log later.
     */
    private static void refuseAnOverrideOfAStepThisApplicationBrought(
            List<StepService> performing, DboWorkerProperties properties) {
        if (properties.getIdentity().getScope() != DboWorkerProperties.Scope.ORGANISATION) {
            return;
        }
        List<String> brought = performing.stream()
                .filter(step -> step.declaration().isPresent())
                .map(StepService::step)
                .toList();
        if (!brought.isEmpty()) {
            throw new IllegalStateException("dbo.worker.identity.scope is 'organisation', and "
                    + brought + " " + (brought.size() == 1 ? "is a step" : "are steps")
                    + " this application brings its own declaration for. Bringing a step is "
                    + "being the rule for it, not varying somebody else's, and a step is not "
                    + "overridable by default \u2014 so every claim of it would be refused. Leave "
                    + "the scope at its default, or do not declare the step here.");
        }
    }

    private static void refuseALaneThatCannotBeUsed(DboWorkerProperties properties) {
        if (properties.getIdentity().getName() == null
                || properties.getIdentity().getName().isBlank()) {
            throw new IllegalStateException("dbo.worker.identity.name is not set. A run records "
                    + "who performed it, and an executor that cannot be reproduced cannot be "
                    + "held to what it did — so this is asked for rather than defaulted to an "
                    + "artifact id.");
        }
        List<String> wrong = new ArrayList<>();
        for (DboWorkerProperties.Lane lane : properties.getLanes()) {
            String named = lane.getTenant() == null ? "a lane with no tenant" : lane.getTenant();
            if (lane.getTenant() == null || lane.getTenant().isBlank()) {
                wrong.add("a lane declares no tenant");
            }
            if (lane.getBase() == null) {
                wrong.add(named + " declares no base, so there is nowhere to be offered work");
            }
            DboWorkerProperties.Token token = lane.getToken();
            boolean carries = token != null && token.getValue() != null
                    && !token.getValue().isBlank();
            boolean signsIn = token != null && token.getClientId() != null
                    && token.getClientSecret() != null;
            if (!carries && !signsIn) {
                wrong.add(named + " has no client and secret to sign in with and no token to "
                        + "carry, so it could never be offered work");
            }
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("this worker's lanes cannot be used as configured: "
                    + String.join("; ", wrong));
        }
    }

    /**
     * A credential an application obtains for itself.
     *
     * <p>The ordinary case is a client and a secret in configuration, which
     * the starter signs in with. This is the other one: a process acting for
     * a person carries that person's token, and a worker enrolled by
     * somebody's fleet carries what its enrolment got it. Both are somebody
     * signing in elsewhere and handing the result over, which is a different
     * act from signing in — so it is a different thing rather than a second
     * spelling of the same property.
     */
    public interface TenantToken extends Supplier<String> {

        /** Which tenant's lane this credential is for. */
        String tenant();
    }
}
