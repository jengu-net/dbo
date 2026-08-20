package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.bundle.core.BundleWatcher;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings the console up: installs the declared bundle set, starts it, and
 * watches what is worth watching.
 *
 * <p>Install every location before starting any, which is what the serving
 * distribution's auto-deploy does — a bundle is then never started against a
 * half-present set. Safe to re-run: installing a location that is already
 * installed returns the existing bundle, and starting an active one does
 * nothing, so this is also how a session picks up a container that is already
 * running.
 */
@Command(scope = "dbo-console", name = "up",
        description = "Installs, starts and watches the dbo bundle set.")
@Service
public class UpCommand implements Action {

    @Reference
    private BundleWatcher watcher;

    @Option(name = "--all",
            description = "Watch every dbo bundle, however much it embeds.")
    private boolean all;

    @Option(name = "--max-embedded",
            description = "Skip watching bundles embedding more than this many megabytes.")
    private long maxEmbeddedMegabytes = 8;

    @Override
    public Object execute() {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();

        List<String> declared = ConsoleBundles.declared(context);
        if (declared.isEmpty()) {
            System.out.println("No bundle set declared. The console assembly writes "
                    + ConsoleBundles.DECLARED_SET_PROPERTY
                    + "; re-run ./gradlew :karaf:console with the container stopped.");
            return null;
        }

        List<Bundle> installed = new ArrayList<>();
        int fresh = 0;
        for (String location : declared) {
            try {
                Bundle before = context.getBundle(location);
                Bundle bundle = context.installBundle(location);
                if (before == null) {
                    fresh++;
                }
                installed.add(bundle);
            } catch (Exception e) {
                System.out.println("Could not install " + location + ": " + e.getMessage());
            }
        }

        int started = 0;
        for (Bundle bundle : installed) {
            if (ConsoleBundles.isFragment(bundle) || bundle.getState() == Bundle.ACTIVE) {
                continue;
            }
            try {
                bundle.start();
                started++;
            } catch (Exception e) {
                // The message here is the finding: a bundle that resolves at
                // build time and cannot start is this project's characteristic
                // failure, and bundle:diag has the detail.
                System.out.println("Could not start " + bundle.getSymbolicName()
                        + ": " + e.getMessage());
            }
        }

        System.out.println("Installed " + installed.size() + " bundles ("
                + fresh + " newly), started " + started + ".");
        WatchCommand.watch(context, watcher, all, maxEmbeddedMegabytes);
        rewireSelf(context);
        return null;
    }

    /**
     * Re-reads this bundle against the set that just arrived.
     *
     * <p>The console's own commands are installed from {@code deploy/} before
     * any dbo bundle exists, so the packages some of them read runs through are
     * optional and unwired at that point. Resolution happened once, at startup,
     * and nothing re-does it on its own: without this, {@code dbo-run:list}
     * would keep saying the bundles are not here while they sat in the list
     * above it.
     *
     * <p>Asynchronous by contract — the framework refreshes after this command
     * returns, which is also why it is the last thing {@code up} does.
     */
    private static void rewireSelf(BundleContext context) {
        if (Runs.available()) {
            return;
        }
        Bundle self = FrameworkUtil.getBundle(UpCommand.class);
        org.osgi.framework.wiring.FrameworkWiring wiring =
                context.getBundle(0).adapt(org.osgi.framework.wiring.FrameworkWiring.class);
        if (wiring == null) {
            return;
        }
        System.out.println("Re-reading the console's own commands against the set — "
                + "dbo-run:list needs a moment.");
        wiring.refreshBundles(List.of(self));
    }
}
