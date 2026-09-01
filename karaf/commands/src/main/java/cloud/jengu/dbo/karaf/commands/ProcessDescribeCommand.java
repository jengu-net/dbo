package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.Session;
import org.osgi.framework.FrameworkUtil;

/**
 * One step in full, and the question this command exists for: <b>which
 * executor would run it here now</b>.
 *
 * <p>That answer currently costs reading three declarations and a chain, which
 * is why "a local implementation and a hospital's own system are two candidates
 * for the same step" is true and unverifiable at a glance. Resolution is
 * deterministic, so the console can simply ask it rather than describe it.
 *
 * <p>Where the work would be happening is a parameter, because it changes the
 * answer: the chain is general to local, and the most local candidate a step
 * admits is the one that takes it.
 *
 * <p>Nothing here names a dbo type — see {@link Wiring}.
 */
@Command(scope = "dbo-process", name = "describe",
        description = "Describes one step, and says which executor would run it here now.")
@Service
public class ProcessDescribeCommand implements Action {

    @Argument(index = 0, name = "step", required = true,
            description = "The step id, as module.process.step.")
    private String step;

    @Option(name = "--tenant", description = "Which tenant's executors to read.")
    private String tenant;

    @Option(name = "--zone", description = "Resolve as if the work were happening in this zone.")
    private String zone;

    @Option(name = "--organisation",
            description = "Resolve as if the work were happening at this organisation.")
    private String organisation;

    @Reference
    private Session session;

    @Override
    public Object execute() {
        if (!Wiring.available()) {
            Wiring.explainAbsence();
            return null;
        }
        ProcessView.describe(FrameworkUtil.getBundle(getClass()).getBundleContext(),
                ConsoleSession.tenantOr(session, tenant), step, zone, organisation);
        return null;
    }
}
