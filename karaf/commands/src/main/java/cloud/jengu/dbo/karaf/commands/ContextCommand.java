package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.Session;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.util.Set;

/**
 * Where the console is standing (#76).
 *
 * <p>Once a command is about <em>a</em> tenant, "which tenant" becomes a flag
 * on every line, and a flag repeated on every line is where the wrong tenant
 * gets typed. A single-tenant deployment needs no flag at all: the tenant is
 * the only one there is.
 *
 * <p>A position, not a permission. Standing somewhere shows what is there;
 * acting there names a person, and that is {@code dbo:login}'s half.
 */
@Command(scope = "dbo", name = "context",
        description = "Stands in a tenant, so commands about one need no flag.")
@Service
public class ContextCommand implements Action {

    @Argument(index = 0, description = "The tenant to stand in. Omitted, it says where you are.")
    @Completion(TenantCompleter.class)
    private String tenant;

    @Option(name = "--clear", description = "Stand nowhere again.")
    private boolean clear;

    @Reference
    private Session session;

    @Override
    public Object execute() {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        if (clear) {
            ConsoleSession.tenant(session, null);
            System.out.println("Standing nowhere. Commands about a tenant need --tenant again.");
            return null;
        }
        Set<String> served = Tenants.codes(context);
        if (tenant == null) {
            where(served);
            return null;
        }
        if (!served.contains(tenant)) {
            // The console shows what is being served, so a tenant it cannot see
            // is either not up or not here — and standing in one that does not
            // exist would make every later command fail somewhere less obvious.
            System.out.println("This node is not serving '" + tenant + "'."
                    + (served.isEmpty() ? " " + Tenants.whyNothingIsServed(context)
                            : " It is serving: " + String.join(", ", served) + "."));
            return null;
        }
        ConsoleSession.tenant(session, tenant);
        System.out.println("Standing in " + tenant + ".");
        return null;
    }

    /** Where you are — including the case where there is only one place to be. */
    private void where(Set<String> served) {
        String standing = ConsoleSession.tenant(session);
        if (standing == null && served.size() == 1) {
            // Not a default written down anywhere: with one tenant there is no
            // choice to make, and asking somebody to state it is ceremony.
            standing = served.iterator().next();
            ConsoleSession.tenant(session, standing);
            System.out.println("Standing in " + standing + " — the only tenant here.");
            return;
        }
        String actor = ConsoleSession.actor(session);
        System.out.println(standing == null
                ? "Standing nowhere. dbo:context <tenant> — this node serves: "
                        + (served.isEmpty() ? "nothing" : String.join(", ", served))
                : "Standing in " + standing + (actor == null
                        ? " as nobody — reads need no identity; acting will."
                        : " as " + actor + ", until " + ConsoleSession.expires(session) + "."));
    }
}
