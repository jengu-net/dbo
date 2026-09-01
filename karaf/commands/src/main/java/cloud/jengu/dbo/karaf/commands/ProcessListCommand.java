package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.Session;
import org.osgi.framework.FrameworkUtil;

/**
 * What this node knows how to do.
 *
 * <p>The local half of the network map: the steps installed modules contribute
 * and the ones linked participants introduced, with where each came from. It
 * answers on a node with <b>no tenant serving at all</b> — the catalogue is
 * what is installed, not what is running — which is the difference between
 * this and every list that reads a store.
 *
 * <p>Nothing here names a dbo type — see {@link Wiring} for why that is a rule
 * rather than a style.
 */
@Command(scope = "dbo-process", name = "list",
        description = "Lists the steps this node knows: installed and introduced.")
@Service
public class ProcessListCommand implements Action {

    @Option(name = "--tenant",
            description = "Only this tenant's introduced steps; installed ones are node-wide.")
    private String tenant;

    @Option(name = "--process", description = "Only this process, as module.process.")
    private String process;

    @Reference
    private Session session;

    @Override
    public Object execute() {
        if (!Wiring.available()) {
            Wiring.explainAbsence();
            return null;
        }
        ProcessView.list(FrameworkUtil.getBundle(getClass()).getBundleContext(),
                ConsoleSession.tenantOr(session, tenant), process);
        return null;
    }
}
