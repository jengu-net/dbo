package cloud.jengu.dbo.karaf.commands;

import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.table.ShellTable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.util.Locale;
import java.util.Map;

/**
 * What this node is holding: automation running, retries scheduled, and — the
 * one an operator opens the console for — work waiting for a person (#75).
 *
 * <p>Held by a person is the default, because a list that shows everything
 * answers "what is happening" while looking like it answered "what needs me".
 *
 * <p><b>Reads, never acts.</b> Retrying, closing and reassigning are declared
 * step actions carrying their own provenance; a console that acts is an actor
 * nobody audited.
 */
@Command(scope = "dbo-run", name = "list",
        description = "Lists runs on this node by holder — by default the ones waiting for a person.")
@Service
public class RunListCommand implements Action {

    @Option(name = "--tenant", description = "Only this tenant's runs.")
    private String tenant;

    @Option(name = "--process", description = "Only this process.")
    private String process;

    @Option(name = "--step", description = "Only this step.")
    private String step;

    @Option(name = "--holder",
            description = "person (default), automation, retry, nobody, or any.")
    private String holder = "person";

    @Option(name = "--limit", description = "How many rows per tenant.")
    private int limit = 50;

    @Override
    public Object execute() {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        Map<String, cloud.jengu.dbo.work.Runs> byTenant = Runs.byTenant(context, tenant);
        if (byTenant.isEmpty()) {
            System.out.println("No tenant is being served here, so there are no runs to read."
                    + " Runs live in the tenant whose work they are.");
            return null;
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
        for (Map.Entry<String, cloud.jengu.dbo.work.Runs> entry : byTenant.entrySet()) {
            for (Run run : entry.getValue().matching(process, step, wanted, limit)) {
                rows++;
                table.addRow().addContent(entry.getKey(), run.process(), run.step(),
                        run.kind().wire(), run.holder().wire(),
                        Runs.tally(run.tally()), run.key());
            }
        }
        if (rows == 0) {
            System.out.println("Nothing is " + (wanted == null ? "recorded" : "held by "
                    + wanted.wire()) + " here.");
            return null;
        }
        table.print(System.out);
        System.out.println();
        System.out.println("dbo-run:describe <key> for the outcomes, the executor and the "
                + "correlation.");
        return null;
    }
}
