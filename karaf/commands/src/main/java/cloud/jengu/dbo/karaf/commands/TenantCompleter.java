package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.CommandLine;
import org.apache.karaf.shell.api.console.Completer;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.support.completers.StringsCompleter;
import org.osgi.framework.FrameworkUtil;

import java.util.List;

/**
 * Completes a tenant code from what the node is currently serving.
 *
 * <p>Asked at completion time rather than cached: tenants come and go while the
 * console is open — a spec dropped into the directory is live within a couple
 * of seconds — and a stale list would offer a tenant that has gone away.
 */
@Service
public class TenantCompleter implements Completer {

    @Override
    public int complete(Session session, CommandLine commandLine, List<String> candidates) {
        StringsCompleter delegate = new StringsCompleter();
        delegate.getStrings().addAll(
                Tenants.codes(FrameworkUtil.getBundle(getClass()).getBundleContext()));
        return delegate.complete(session, commandLine, candidates);
    }
}
