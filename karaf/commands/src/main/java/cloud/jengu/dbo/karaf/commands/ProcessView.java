package cloud.jengu.dbo.karaf.commands;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.work.Automation;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.ExecutorResolution;
import cloud.jengu.dbo.work.Introductions;
import cloud.jengu.dbo.work.Resolution;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.ScopeClass;
import cloud.jengu.dbo.work.StepGrant;
import cloud.jengu.dbo.work.Work;
import org.apache.karaf.shell.support.table.ShellTable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reading the catalogue, which is the only part of these commands that names a
 * dbo type — kept out of the command classes for the reason {@link Wiring}
 * gives.
 *
 * <p><b>The catalogue has two doors and this reads both.</b> A module
 * contributes steps by being installed, and a linked participant introduces
 * the step it performs (#147). An operator asking what this node knows how to
 * do wants one answer, with the provenance beside each row rather than two
 * lists to reconcile.
 *
 * <p><b>Installed is node-wide; introduced is a tenant's.</b> So the installed
 * half answers on a node with no tenant serving at all — which is the point of
 * "the catalogue is what is installed, not what is running" — and the
 * introduced half is read per tenant, like runs are.
 *
 * <p><b>Names no content.</b> A step declaration is about a shape, never about
 * anybody; the only free text here is an executor's provider and a resolution's
 * reason, both of which are about machinery.
 */
final class ProcessView {

    /**
     * How long a participant may be behind and unmoving before its declaration
     * stops counting as present — the same patience the runtime uses, so the
     * console's answer and the store's agree about who is answering.
     */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private ProcessView() {
    }

    /** What this node knows how to do, installed and introduced. */
    static void list(BundleContext context, String tenant, String process) {
        ShellTable table = new ShellTable();
        table.column("step");
        table.column("reads");
        table.column("writes");
        table.column("overridable");
        table.column("contributed by");

        Map<String, String[]> rows = new TreeMap<>();
        installed(context).forEach((step, bundle) -> {
            if (process == null || step.id().processId().equals(process)) {
                rows.put(step.id().toString(), row(step, bundle));
            }
        });
        for (Map.Entry<String, ObjectStore> served : stores(context, tenant).entrySet()) {
            for (Introductions.Introduced introduced
                    : new Introductions(served.getValue(), Steps.of()).all()) {
                StepDeclaration step = introduced.step();
                if (process != null && !step.id().processId().equals(process)) {
                    continue;
                }
                // Introduced by a participant, into one tenant's store — so the
                // provenance says whose and where, which "installed" never
                // needs to.
                rows.put(step.id().toString(), row(step,
                        "introduced by " + introduced.introducer() + " (" + served.getKey() + ")"));
            }
        }
        rows.values().forEach(row -> table.addRow().addContent((Object[]) row));
        if (rows.isEmpty()) {
            System.out.println("No steps are declared here. A module contributes them by being "
                    + "installed, and a linked participant introduces the one it performs.");
            return;
        }
        table.print(System.out);
    }

    private static String[] row(StepDeclaration step, String source) {
        return new String[] {
                step.id().toString(),
                String.join(",", new java.util.TreeSet<>(step.reads())),
                String.join(",", new java.util.TreeSet<>(step.writes())),
                step.overridable().orElse("nobody"),
                source,
        };
    }

    /**
     * One step in full, and the question an operator opens this for: which
     * executor would run it here now, and why that one.
     */
    static void describe(BundleContext context, String tenant, String id, String zone,
            String organisation) {
        Optional<StepDeclaration> found = installed(context).keySet().stream()
                .filter(step -> step.id().toString().equals(id)).findFirst();
        Map<String, ObjectStore> served = stores(context, tenant);
        for (Map.Entry<String, ObjectStore> store : served.entrySet()) {
            if (found.isPresent()) {
                break;
            }
            found = new Introductions(store.getValue(), Steps.of()).all().stream()
                    .map(Introductions.Introduced::step)
                    .filter(step -> step.id().toString().equals(id)).findFirst();
        }
        if (found.isEmpty()) {
            System.out.println("No step '" + id + "' is declared here. dbo-process:list shows "
                    + "what is — a step referenced but not contributed is refused by name "
                    + "rather than silently doing nothing.");
            return;
        }
        StepDeclaration step = found.get();

        System.out.println("step         " + step.id());
        System.out.println("version      " + step.version());
        System.out.println("reads        " + new java.util.TreeSet<>(step.reads()));
        System.out.println("writes       " + new java.util.TreeSet<>(step.writes()));
        System.out.println("consumes     " + step.consumes().orElse("(constrains nothing)"));
        System.out.println("produces     " + step.produces().orElse("(constrains nothing)"));
        System.out.println("overridable  " + step.overridable().orElse("nobody"));
        System.out.println("actions      " + (step.actions().isEmpty()
                ? "(has not said — which is not the same as admits nothing)"
                : new java.util.TreeSet<>(step.actions())));
        System.out.println("slots        " + (step.slots().isEmpty() ? "(none)" : step.slots()));
        System.out.println("milestones   " + (step.milestones().isEmpty()
                ? "(has not said)" : step.milestones()));

        if (served.isEmpty()) {
            System.out.println();
            System.out.println("No tenant is being served here, so there is nobody to run it: "
                    + "executors are a tenant's declarations, and the catalogue is not.");
            return;
        }
        List<Scope> chain = chain(zone, organisation);
        served.forEach((code, store) -> describeExecutors(context, code, store, step, chain));
    }

    /** Who claims this step in one tenant, and which of them would take it. */
    private static void describeExecutors(BundleContext context, String code, ObjectStore store,
            StepDeclaration step, List<Scope> chain) {
        ChangeFeed feed = feedOf(context, code);
        if (feed == null) {
            return;
        }
        Declarations declarations = new Declarations(store, feed, PATIENCE);
        String process = step.id().processId();
        String bare = step.id().step();

        System.out.println();
        System.out.println("tenant " + code + " — declared executors, most local last");
        ShellTable table = new ShellTable();
        table.column("executor");
        table.column("version");
        table.column("provider");
        table.column("scope");
        table.column("present");
        table.column("vitals");
        boolean any = false;
        for (Declarations.Declared declared : declarations.all()) {
            if (!declared.process().equals(process) || !declared.step().equals(bare)) {
                continue;
            }
            any = true;
            // Declared and not present is a different sentence from nothing
            // declared, and an operator needs to tell them apart: presence is
            // derived from the cursor, never from what a participant says
            // about itself.
            table.addRow().addContent(declared.name(), declared.version(), declared.provider(),
                    declared.scope().wire(),
                    declarations.candidates().stream()
                            .anyMatch(candidate -> candidate.executor().name()
                                    .equals(declared.name())) ? "yes" : "no",
                    vitals(declared.metadata()));
        }
        if (!any) {
            System.out.println("  nothing declares it here, so it is held by a person — "
                    + "manual is the baseline, not an absence.");
        } else {
            table.print(System.out);
        }

        StepGrant grant = step.overridable()
                .map(scopeClass -> StepGrant.of(process, bare)
                        .overridableBy(ScopeClass.valueOf(scopeClass.toUpperCase(Locale.ROOT))))
                .orElseGet(() -> StepGrant.of(process, bare));
        Resolution resolution = new ExecutorResolution(declarations::candidates)
                .resolve(grant, chain, List.<Automation>of(), Work.of(process, bare, null));
        System.out.println("  would run here: " + (resolution.executor() == null
                ? "nobody — " + resolution.reason() + ", so a person holds it"
                : resolution.executor().name() + " at "
                        + resolution.executor().scope().wire()));
        if (resolution.refused() != null) {
            // A refused override is a fact about somebody's rule and does not
            // stop being one because the step's own executor ran.
            System.out.println("  refused on the way: " + resolution.refused());
        }
        System.out.println("  chain: " + chain.stream().map(Scope::wire).toList());
    }

    /**
     * What a participant last said about itself (#148), beside the presence
     * this node worked out for itself.
     *
     * <p><b>The order of those two columns is the point.</b> Presence is
     * derived from the cursor, because a component that is stuck keeps
     * reporting that it is fine — that is exactly the lie derived presence
     * exists to catch. So a row reading {@code present=no} beside healthy
     * numbers is not a contradiction to be resolved; it is the answer, and
     * the numbers are the last thing the participant claimed before it
     * stopped.
     *
     * <p>Rendered opaque, in whatever keys arrived. The engine stores these
     * the way it stores shapes and a component kind nobody has met yet will
     * bring keys nobody has named, so a fixed set of columns here would
     * quietly drop them.
     */
    static String vitals(Map<String, String> block) {
        if (block == null || block.isEmpty()) {
            return "(said nothing)";
        }
        StringBuilder rendered = new StringBuilder();
        block.forEach((key, value) -> rendered.append(rendered.isEmpty() ? "" : " ")
                .append(key).append('=').append(value));
        return rendered.toString();
    }

    /** Where the work would be happening, general to local. */
    private static List<Scope> chain(String zone, String organisation) {
        List<Scope> chain = new ArrayList<>();
        chain.add(Scope.BASELINE);
        if (zone != null) {
            chain.add(Scope.zone(zone));
        }
        if (organisation != null) {
            chain.add(Scope.organisation(organisation));
        }
        return chain;
    }

    /**
     * The steps every installed catalogue contributes, and the bundle each
     * came from.
     *
     * <p>Asked of the registry rather than the classpath, for the reason
     * {@code dbo-tenant:list} asks it too: what a module contributes is what
     * it contributes <em>while installed</em>, and a console that read a file
     * would answer for a node that no longer exists.
     */
    private static Map<StepDeclaration, String> installed(BundleContext context) {
        Map<StepDeclaration, String> steps = new LinkedHashMap<>();
        try {
            ServiceReference<?>[] references =
                    context.getAllServiceReferences(Steps.Catalogue.class.getName(), null);
            if (references == null) {
                return steps;
            }
            for (ServiceReference<?> reference : references) {
                Object service = context.getService(reference);
                if (service instanceof Steps.Catalogue catalogue) {
                    String bundle = reference.getBundle() == null
                            ? "(gone)" : reference.getBundle().getSymbolicName();
                    catalogue.steps().forEach(step -> steps.put(step, bundle));
                }
            }
        } catch (org.osgi.framework.InvalidSyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
        return steps;
    }

    /** The tenant stores this node serves, filtered to one when asked. */
    private static Map<String, ObjectStore> stores(BundleContext context, String only) {
        Map<String, ObjectStore> stores = new TreeMap<>();
        try {
            ServiceReference<?>[] references =
                    context.getAllServiceReferences(ObjectStore.class.getName(), null);
            if (references == null) {
                return stores;
            }
            for (ServiceReference<?> reference : references) {
                Object code = reference.getProperty(Tenants.TENANT_PROPERTY);
                if (code == null || (only != null && !only.equals(String.valueOf(code)))) {
                    continue;
                }
                if (context.getService(reference) instanceof ObjectStore store) {
                    stores.put(String.valueOf(code), store);
                }
            }
        } catch (org.osgi.framework.InvalidSyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
        return stores;
    }

    /** A tenant's change feed, which is where presence is read from. */
    private static ChangeFeed feedOf(BundleContext context, String code) {
        try {
            ServiceReference<?>[] references =
                    context.getAllServiceReferences(ChangeFeed.class.getName(), null);
            if (references == null) {
                return null;
            }
            for (ServiceReference<?> reference : references) {
                if (code.equals(String.valueOf(reference.getProperty(Tenants.TENANT_PROPERTY)))
                        && context.getService(reference) instanceof ChangeFeed feed) {
                    return feed;
                }
            }
        } catch (org.osgi.framework.InvalidSyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
        return null;
    }
}
