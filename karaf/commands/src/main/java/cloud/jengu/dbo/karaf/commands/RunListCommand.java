package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.osgi.framework.FrameworkUtil;

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
 *
 * <p>Nothing here names a dbo type — see {@link Wiring} for why that is a rule
 * rather than a style.
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
        if (!Wiring.available()) {
            Wiring.explainAbsence();
            return null;
        }
        RunView.list(FrameworkUtil.getBundle(getClass()).getBundleContext(),
                tenant, process, step, holder, limit);
        return null;
    }
}
