package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.CommandLine;
import org.apache.karaf.shell.api.console.Completer;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.support.completers.StringsCompleter;
import org.osgi.framework.FrameworkUtil;

import java.util.List;

/**
 * Completes a run key from what the tenants on this node are actually holding.
 *
 * <p>A run key is a path somebody would otherwise copy by hand, and the console
 * is the only place it appears. Asked at completion time, like the tenant
 * completer, because runs are written continuously and a cached list offers the
 * state of a minute ago.
 *
 * <p>Nothing here names a dbo type — see {@link Wiring} for why that is a rule
 * rather than a style.
 */
@Service
public class RunKeyCompleter implements Completer {

    @Override
    public int complete(Session session, CommandLine commandLine, List<String> candidates) {
        StringsCompleter delegate = new StringsCompleter();
        if (Wiring.available()) {
            try {
                delegate.getStrings().addAll(
                        RunView.keys(FrameworkUtil.getBundle(getClass()).getBundleContext()));
            } catch (RuntimeException nothingToOffer) {
                // Completion is a convenience: a tenant that cannot be read
                // offers nothing rather than failing the keystroke.
                return delegate.complete(session, commandLine, candidates);
            }
        }
        return delegate.complete(session, commandLine, candidates);
    }
}
