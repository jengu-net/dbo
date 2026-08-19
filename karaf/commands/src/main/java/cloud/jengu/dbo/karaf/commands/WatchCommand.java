package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.bundle.core.BundleWatcher;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Watches the dbo bundles this container actually has installed.
 *
 * <p>The set is derived from the running container rather than from a list
 * written down somewhere: every installed bundle resolved from this project's
 * Maven coordinates is a candidate, so a bundle added to the distribution is
 * watched the next time this runs, with nothing to remember to update.
 *
 * <p>Bundles that embed a large private stack are skipped by default. The
 * reason to skip is cost — a republish rewrites every jar, so watching a
 * hundred-megabyte bundle means re-reading it on every publish for a change
 * that is almost never in it — so the measure is the weight of what is
 * embedded, not the fact of embedding. A module can carry a small private jar
 * and still be one of the ones worth watching.
 */
@Command(scope = "dbo", name = "watch",
        description = "Watches the installed dbo bundles for republished changes.")
@Service
public class WatchCommand implements Action {

    /** The coordinates this project publishes under; the location prefix of anything it built. */
    private static final String LOCATION_PREFIX = "mvn:cloud.jengu.dbo/";

    private static final long MEGABYTE = 1024L * 1024L;

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
        long limit = maxEmbeddedMegabytes * MEGABYTE;

        List<String> toWatch = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Bundle bundle : context.getBundles()) {
            String location = bundle.getLocation();
            if (location == null || !location.startsWith(LOCATION_PREFIX)) {
                continue;
            }
            long embedded = embeddedBytes(bundle);
            if (!all && embedded > limit) {
                skipped.add(bundle.getSymbolicName() + " (" + (embedded / MEGABYTE) + "M)");
            } else {
                toWatch.add(location);
            }
        }

        if (toWatch.isEmpty()) {
            System.out.println("No dbo bundles are installed from " + LOCATION_PREFIX
                    + " — nothing to watch.");
            return null;
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
        if (!skipped.isEmpty()) {
            System.out.println("Skipped, embedding more than " + maxEmbeddedMegabytes
                    + "M: " + String.join(", ", skipped));
            System.out.println("  --all watches them too; --max-embedded moves the line.");
        }
        return null;
    }

    /**
     * The weight of the jars nested inside a bundle. Zero for a bundle whose
     * {@code Bundle-ClassPath} is absent or names only the bundle itself.
     */
    private static long embeddedBytes(Bundle bundle) {
        String classPath = bundle.getHeaders().get(Constants.BUNDLE_CLASSPATH);
        if (classPath == null || classPath.isBlank()) {
            return 0;
        }
        long total = 0;
        for (String element : classPath.split(",")) {
            String entry = element.trim();
            if (entry.isEmpty() || entry.equals(".")) {
                continue;
            }
            total += sizeOf(bundle, entry);
        }
        return total;
    }

    private static long sizeOf(Bundle bundle, String entry) {
        URL url = bundle.getEntry(entry);
        if (url == null) {
            return 0;
        }
        try {
            return Math.max(0, url.openConnection().getContentLengthLong());
        } catch (Exception e) {
            // An unreadable entry says nothing about how heavy the bundle is;
            // treating it as weightless keeps the bundle watched, which is the
            // recoverable direction to be wrong in.
            return 0;
        }
    }
}
