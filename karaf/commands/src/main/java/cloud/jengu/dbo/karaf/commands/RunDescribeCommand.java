package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.osgi.framework.FrameworkUtil;

/**
 * One run in full: what it did, what is left, who ran it, and what it was
 * given (#75).
 *
 * <p>Item outcomes name a record and a reason, which is what somebody needs in
 * order to act. Nothing else here names anything: the envelope is a disclosure
 * surface and a console line must not become the one it is not allowed to be.
 *
 * <p>Nothing here names a dbo type — see {@link Wiring} for why that is a rule
 * rather than a style.
 */
@Command(scope = "dbo-run", name = "describe",
        description = "Shows one run: tally, item outcomes, executor and correlation.")
@Service
public class RunDescribeCommand implements Action {

    @Argument(index = 0, description = "The run's key. Without one, the keys there are.")
    @Completion(RunKeyCompleter.class)
    private String key;

    @Option(name = "--tenant", description = "Where to look; every tenant by default.")
    private String tenant;

    @Override
    public Object execute() {
        if (!Wiring.available()) {
            Wiring.explainAbsence();
            return null;
        }
        RunView.describe(FrameworkUtil.getBundle(getClass()).getBundleContext(), tenant, key);
        return null;
    }
}
