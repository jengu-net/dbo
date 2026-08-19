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
 * Watches the dbo bundles this container has installed.
 *
 * <p>The set is derived from the running container rather than from a list
 * written down somewhere, so a bundle added to the distribution is watched the
 * next time this runs, with nothing to remember to update.
 *
 * <p>Bundles that embed a large private stack are skipped by default. The
 * reason to skip is cost — a republish rewrites every jar, so watching a
 * hundred-megabyte bundle means re-reading it on every publish for a change
 * that is almost never in it — so the measure is the weight of what is
 * embedded, not the fact of embedding. A module can carry a small private jar
 * and still be one of the ones worth watching.
 */
@Command(scope = "dbo-console", name = "watch",
        description = "Watches the installed dbo bundles for republished changes.")
@Service
public class WatchCommand implements Action {

    @Reference
    private BundleWatcher watcher;

    @Option(name = "--all",
            description = "Watch every dbo bundle, however much it embeds.")
    private boolean all;

    @Option(name = "--max-embedded",
            description = "Skip bundles embedding more than this many megabytes.")
    private long maxEmbeddedMegabytes = 8;

    @Override
    public Object execute() {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        watch(context, watcher, all, maxEmbeddedMegabytes);
        return null;
    }

    /** Shared with {@code dbo-console:up}, which finishes by doing exactly this. */
    static void watch(BundleContext context, BundleWatcher watcher,
            boolean all, long maxEmbeddedMegabytes) {
        long limit = maxEmbeddedMegabytes * ConsoleBundles.MEGABYTE;

        List<String> toWatch = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> unwatchable = new ArrayList<>();
        for (Bundle bundle : ConsoleBundles.installed(context)) {
            // A fragment cannot be updated in place — its host has to refresh —
            // and the logging binding must not be updated at all: re-reading it
            // is exactly how the container goes quiet, and a container that has
            // gone quiet cannot tell you that it has. Neither is a candidate
            // however small it is, so this is decided before the size is.
            if (ConsoleBundles.isFragment(bundle) || ConsoleBundles.isLoggingBinding(bundle)) {
                unwatchable.add(bundle.getSymbolicName());
                continue;
            }
            long embedded = ConsoleBundles.embeddedBytes(bundle);
            if (!all && embedded > limit) {
                skipped.add(bundle.getSymbolicName()
                        + " (" + (embedded / ConsoleBundles.MEGABYTE) + "M)");
            } else {
                toWatch.add(bundle.getLocation());
            }
        }

        if (toWatch.isEmpty()) {
            System.out.println("No dbo bundles are installed from "
                    + ConsoleBundles.LOCATION_PREFIX + " — nothing to watch."
                    + " dbo-console:up installs them.");
            return;
        }

        List<String> already = watcher.getWatchURLs();
        int added = 0;
        for (String location : toWatch) {
            if (!already.contains(location)) {
                watcher.add(location);
                added++;
            }
        }
        watcher.start();

        System.out.println("Watching " + toWatch.size() + " dbo bundles ("
                + added + " newly added).");
        if (!unwatchable.isEmpty()) {
            System.out.println("Not watchable: " + String.join(", ", unwatchable)
                    + " — a fragment, or the logging binding itself.");
        }
        if (!skipped.isEmpty()) {
            System.out.println("Skipped, embedding more than " + maxEmbeddedMegabytes
                    + "M: " + String.join(", ", skipped));
            System.out.println("  --all watches them too; --max-embedded moves the line.");
        }
    }
}
