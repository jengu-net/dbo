package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.CommandLine;
import org.apache.karaf.shell.api.console.Completer;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.support.completers.StringsCompleter;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.util.List;

/**
 * Completes a capability name from the statement of the tenant already typed.
 *
 * <p>Which means reading the command line: the names depend on the first
 * argument, and offering the union across tenants would suggest facts that
 * tenant does not have. If no tenant has been typed yet there is nothing
 * truthful to offer, so nothing is.
 */
@Service
public class CapabilityNameCompleter implements Completer {

    @Override
    public int complete(Session session, CommandLine commandLine, List<String> candidates) {
        String tenant = tenantArgument(commandLine);
        if (tenant == null) {
            return -1;
        }
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        if (!Tenants.codes(context).contains(tenant)) {
            return -1;
        }

        StringsCompleter delegate = new StringsCompleter();
        try {
            Capabilities.rows(Capabilities.fetch(Capabilities.baseUrl(context, tenant)), false)
                    .forEach(row -> delegate.getStrings().add(row.name()));
        } catch (Exception e) {
            // A tenant that cannot be read has no names to offer. Completion is
            // not the place to report it; running the command says so plainly.
            return -1;
        }
        return delegate.complete(session, commandLine, candidates);
    }

    /**
     * The first positional token after the command name, skipping options and
     * the token the cursor is sitting in.
     */
    private static String tenantArgument(CommandLine commandLine) {
        String[] arguments = commandLine.getArguments();
        for (int i = 1; i < arguments.length; i++) {
            if (i == commandLine.getCursorArgumentIndex()) {
                continue;
            }
            String argument = arguments[i];
            if (argument != null && !argument.isEmpty() && !argument.startsWith("-")) {
                return argument;
            }
        }
        return null;
    }
}
