package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.table.ShellTable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import javax.json.JsonObject;

import java.util.List;

/**
 * A tenant's CapabilityStatement as a table of facts, or one fact from it.
 *
 * <p>The statement is generated from what each configured type declares rather
 * than from a list applied to every type, so reading it back is how a spec
 * file's declarations become visible as the promises clients are given: a type
 * whose identity is store-assigned has nothing to key a conditional write on,
 * and that shows up here as {@code conditionalCreate false}.
 */
@Command(scope = "dbo-tenant", name = "capability-list",
        description = "Shows a tenant's CapabilityStatement as a table of facts.")
@Service
public class TenantCapabilityListCommand implements Action {

    @Argument(index = 0, name = "tenant", required = true,
            description = "The tenant code, as dbo-tenant:list reports it.")
    @Completion(TenantCompleter.class)
    private String tenant;

    @Argument(index = 1, name = "capability", required = false,
            description = "One capability name. A *.searchParam name opens into its"
                    + " parameters; anything else prints just the value.")
    @Completion(CapabilityNameCompleter.class)
    private String capability;

    @Option(name = "--search-params",
            description = "One row per search parameter instead of a count by kind.")
    private boolean searchParams;

    @Override
    public Object execute() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();

        if (!Tenants.codes(context).contains(tenant)) {
            System.out.println("No tenant '" + tenant + "' is being served."
                    + " dbo-tenant:list shows the ones that are.");
            return null;
        }

        String base = Capabilities.baseUrl(context, tenant);
        JsonObject statement;
        try {
            statement = Capabilities.fetch(base);
        } catch (Exception e) {
            System.out.println("Could not read " + base + "/metadata: " + e);
            return null;
        }

        if (capability == null) {
            print(Capabilities.rows(statement, searchParams));
            return null;
        }

        // A summarised row can be opened; a stated one has nothing behind it.
        if (capability.endsWith("." + Capabilities.SEARCH_PARAM)) {
            String entity = capability.substring(
                    0, capability.length() - Capabilities.SEARCH_PARAM.length() - 1);
            List<Capabilities.Row> detail = Capabilities.searchParams(statement, entity);
            if (detail.isEmpty()) {
                System.out.println("No search parameters under '" + capability + "'.");
                return null;
            }
            print(detail);
            return null;
        }

        // Bare, so a single fact can be read by eye or by a script without a
        // table to cut apart.
        for (Capabilities.Row row : Capabilities.rows(statement, true)) {
            if (row.name().equals(capability)) {
                System.out.println(row.value());
                return null;
            }
        }
        System.out.println("No capability '" + capability + "' for tenant '" + tenant
                + "'. Run without it to see the names.");
        return null;
    }

    private static void print(List<Capabilities.Row> rows) {
        ShellTable table = new ShellTable();
        table.column("TYPE");
        table.column("NAME");
        table.column("VALUE");
        for (Capabilities.Row row : rows) {
            table.addRow().addContent(row.type(), row.name(), row.value());
        }
        table.print(System.out);
    }
}
