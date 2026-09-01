package cloud.jengu.dbo.karaf.commands;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import org.apache.karaf.shell.support.table.ShellTable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reading runs, which is the only part of these commands that names a dbo type.
 *
 * <p>Kept out of the command classes deliberately: Karaf reflects over every
 * {@code @Service} action, and a declared signature naming a class that is not
 * wired takes the whole bundle's command registration down (see {@link
 * Wiring}). Nothing reflects on this class, and it is loaded only after the
 * guard says the packages are here.
 *
 * <p>Runs are a tenant's records, including the management tenant's for
 * tenant-lifecycle work, so there is no node-wide list to read — this
 * asks each tenant the node is serving.
 *
 * <p><b>It binds a tenant-plane service from the registry</b>, which the console
 * plan names as the thing a shipped console must not do: the operator is
 * deliberately less privileged than the tenant. It is acceptable here for one
 * reason — this bundle is not in the serving distribution. It is development
 * tooling, dropped into a deploy folder by somebody who already holds the
 * database credentials. A console that ships reads this through the
 * authenticated surface with a session behind it, and it is not this.
 */
final class RunView {

    private RunView() {
    }

    /** Prints the runs somebody asked for. */
    static void list(BundleContext context, String tenant, String process, String step,
            String holder, int limit) {
        Map<String, Runs> byTenant = byTenant(context, tenant);
        if (byTenant.isEmpty()) {
            System.out.println("There are no runs to read: runs live in the tenant whose work"
                    + " they are, and no tenant is being served here.");
            System.out.println(Tenants.whyNothingIsServed(context));
            return;
        }
        Holder wanted = "any".equalsIgnoreCase(holder) ? null
                : Holder.valueOf(holder.toUpperCase(Locale.ROOT));

        ShellTable table = new ShellTable();
        table.column("TENANT");
        table.column("PROCESS");
        table.column("STEP");
        table.column("KIND");
        table.column("HOLDER");
        table.column("TALLY");
        table.column("KEY");
        int rows = 0;
        for (Map.Entry<String, Runs> entry : byTenant.entrySet()) {
            for (Run run : entry.getValue().matching(process, step, wanted, limit)) {
                rows++;
                table.addRow().addContent(entry.getKey(), run.process(), run.step(),
                        run.kind().wire(), run.holder().wire(), tally(run), run.key());
            }
        }
        if (rows == 0) {
            System.out.println("Nothing is " + (wanted == null ? "recorded" : "held by "
                    + wanted.wire()) + " here.");
            return;
        }
        table.print(System.out);
        System.out.println();
        System.out.println("dbo-run:describe <key> for the outcomes, the executor and the "
                + "correlation.");
    }

    /** Prints one run, or — with no key — the keys there are to name. */
    static void describe(BundleContext context, String tenant, String key) {
        Map<String, Runs> byTenant = byTenant(context, tenant);
        if (key == null) {
            // A key is a path nobody memorises, and this is the only place it
            // exists. Naming the missing argument tells somebody what they
            // typed wrong; showing the keys tells them what to type next.
            offerKeys(byTenant, tenant);
            return;
        }
        for (Map.Entry<String, Runs> entry : byTenant.entrySet()) {
            Optional<Run> found = entry.getValue().byKey(key);
            if (found.isPresent()) {
                print(entry.getKey(), entry.getValue(), found.get());
                return;
            }
        }
        System.out.println("No run with that key is recorded"
                + (tenant == null ? " in any tenant this node serves." : " in " + tenant + "."));
    }

    /** The keys a completer can offer, newest first. */
    static List<String> keys(BundleContext context) {
        List<String> keys = new ArrayList<>();
        byTenant(context, null).values().forEach(runs ->
                runs.matching(null, null, null, 100).forEach(run -> keys.add(run.key())));
        return keys;
    }

    private static void offerKeys(Map<String, Runs> byTenant, String tenant) {
        ShellTable table = new ShellTable();
        table.column("TENANT");
        table.column("HOLDER");
        table.column("KEY");
        int rows = 0;
        for (Map.Entry<String, Runs> entry : byTenant.entrySet()) {
            for (Run run : entry.getValue().matching(null, null, null, 20)) {
                rows++;
                table.addRow().addContent(entry.getKey(), run.holder().wire(), run.key());
            }
        }
        if (rows == 0) {
            System.out.println("Nothing is recorded here yet, so there is nothing to describe.");
            return;
        }
        System.out.println("Which run? Newest first" + (tenant == null ? "" : " in " + tenant)
                + " — tab completes these:");
        System.out.println();
        table.print(System.out);
    }

    private static void print(String tenant, Runs runs, Run run) {
        ShellTable facts = new ShellTable();
        facts.column("FACT");
        facts.column("VALUE");
        facts.addRow().addContent("tenant", tenant);
        facts.addRow().addContent("process", run.process());
        facts.addRow().addContent("step", run.step());
        facts.addRow().addContent("kind", run.kind().wire());
        facts.addRow().addContent("holder", run.holder().wire());
        facts.addRow().addContent("domains", String.join(", ", run.domains()));
        facts.addRow().addContent("tally", tally(run));
        // echoed, never interpreted: it is the other system's word
        run.correlated().ifPresent(correlation ->
                facts.addRow().addContent("correlation", correlation));
        if (run.assignment() != null) {
            Run.Assignment assignment = run.assignment();
            if (assignment.at() != null) {
                facts.addRow().addContent("at", assignment.at().wire());
            }
            if (assignment.executor() != null) {
                facts.addRow().addContent("executor", assignment.executor().name() + " "
                        + assignment.executor().version() + " ("
                        + assignment.executor().provider() + ") from "
                        + assignment.executor().scope().wire());
            }
            if (assignment.note() != null) {
                facts.addRow().addContent("note", assignment.note());
            }
        }
        facts.print(System.out);

        List<Run> items = runs.items(run);
        if (items.isEmpty()) {
            return;
        }
        System.out.println();
        ShellTable outcomes = new ShellTable();
        outcomes.column("HOLDER");
        outcomes.column("CLASS");
        outcomes.column("REFERENCE");
        outcomes.column("REASON");
        for (Run item : items) {
            if (item.item() == null) {
                continue;
            }
            outcomes.addRow().addContent(item.holder().wire(),
                    item.item().failure() == null ? "" : item.item().failure().wire(),
                    item.item().reference(), item.item().message());
        }
        outcomes.print(System.out);
    }

    /** A tally as one column, in the order the run kept it. */
    private static String tally(Run run) {
        StringBuilder text = new StringBuilder();
        run.tally().forEach((name, count) -> text.append(text.isEmpty() ? "" : " ")
                .append(name).append('=').append(count));
        return text.toString();
    }

    private static Map<String, Runs> byTenant(BundleContext context, String only) {
        Map<String, Runs> stores = new TreeMap<>();
        ServiceReference<?>[] references;
        try {
            references = context.getServiceReferences(ObjectStore.class.getName(), null);
        } catch (org.osgi.framework.InvalidSyntaxException e) {
            throw new IllegalStateException(e);
        }
        if (references == null) {
            return stores;
        }
        for (ServiceReference<?> reference : references) {
            Object code = reference.getProperty(Tenants.TENANT_PROPERTY);
            if (code == null || (only != null && !only.equals(String.valueOf(code)))) {
                continue;
            }
            if (context.getService(reference) instanceof ObjectStore store) {
                stores.put(String.valueOf(code), new Runs(store));
            }
        }
        return stores;
    }
}
