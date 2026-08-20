package cloud.jengu.dbo.karaf.commands;

import cloud.jengu.dbo.work.Run;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.table.ShellTable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.util.List;
import java.util.Map;

/**
 * One run in full: what it did, what is left, who ran it, and what it was
 * given (#75).
 *
 * <p>Item outcomes name a record and a reason, which is what somebody needs in
 * order to act. Nothing else here names anything: the envelope is a disclosure
 * surface and a console line must not become the one it is not allowed to be.
 */
@Command(scope = "dbo-run", name = "describe",
        description = "Shows one run: tally, item outcomes, executor and correlation.")
@Service
public class RunDescribeCommand implements Action {

    @Argument(index = 0, required = true, description = "The run's key.")
    private String key;

    @Option(name = "--tenant", description = "Where to look; every tenant by default.")
    private String tenant;

    @Override
    public Object execute() {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        for (Map.Entry<String, cloud.jengu.dbo.work.Runs> entry
                : Runs.byTenant(context, tenant).entrySet()) {
            java.util.Optional<Run> found = entry.getValue().byKey(key);
            if (found.isPresent()) {
                print(entry.getKey(), entry.getValue(), found.get());
                return null;
            }
        }
        System.out.println("No run with that key is recorded"
                + (tenant == null ? " in any tenant this node serves." : " in " + tenant + "."));
        return null;
    }

    private static void print(String tenant, cloud.jengu.dbo.work.Runs runs, Run run) {
        ShellTable facts = new ShellTable();
        facts.column("FACT");
        facts.column("VALUE");
        facts.addRow().addContent("tenant", tenant);
        facts.addRow().addContent("process", run.process());
        facts.addRow().addContent("step", run.step());
        facts.addRow().addContent("kind", run.kind().wire());
        facts.addRow().addContent("holder", run.holder().wire());
        facts.addRow().addContent("domains", String.join(", ", run.domains()));
        facts.addRow().addContent("tally", Runs.tally(run.tally()));
        run.correlated().ifPresent(correlation ->
                // echoed, never interpreted: it is the other system's word
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
}
