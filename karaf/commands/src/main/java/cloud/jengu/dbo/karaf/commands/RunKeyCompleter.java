package cloud.jengu.dbo.karaf.commands;

import cloud.jengu.dbo.work.Run;
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
 * <p>A run key is a path somebody would have to copy by hand, and the console
 * is the only place it appears. Asked at completion time, like the tenant
 * completer, because runs are written continuously — a cached list would offer
 * the state of a minute ago.
 */
@Service
public class RunKeyCompleter implements Completer {

    @Override
    public int complete(Session session, CommandLine commandLine, List<String> candidates) {
        StringsCompleter delegate = new StringsCompleter();
        if (Runs.available()) {
            // Completion is a convenience: a tenant that cannot be read offers
            // nothing rather than failing the keystroke.
            try {
                Runs.byTenant(FrameworkUtil.getBundle(getClass()).getBundleContext(), null)
                        .values().forEach(runs -> runs.matching(null, null, null, 100).stream()
                                .map(Run::key).forEach(delegate.getStrings()::add));
            } catch (RuntimeException nothingToOffer) {
                return delegate.complete(session, commandLine, candidates);
            }
        }
        return delegate.complete(session, commandLine, candidates);
    }
}
