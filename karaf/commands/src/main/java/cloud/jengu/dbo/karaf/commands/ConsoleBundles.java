package cloud.jengu.dbo.karaf.commands;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * What the console knows about the bundles it serves.
 *
 * <p>The declared set — what {@code dbo-console:up} installs — arrives as a property
 * the assembly writes, because before the first install the container has no
 * way to know what it is meant to hold. Everything after that is read from the
 * container itself.
 */
final class ConsoleBundles {

    /** The coordinates this project publishes under; the location prefix of anything it built. */
    static final String LOCATION_PREFIX = "mvn:cloud.jengu.dbo/";

    /** Written by the console assembly from the same list the distribution installs. */
    static final String DECLARED_SET_PROPERTY = "dbo.console.bundles";

    static final long MEGABYTE = 1024L * 1024L;

    private ConsoleBundles() {
    }

    /** The locations the assembly declared, in install order. */
    static List<String> declared(BundleContext context) {
        String declared = context.getProperty(DECLARED_SET_PROPERTY);
        if (declared == null || declared.isBlank()) {
            return List.of();
        }
        List<String> locations = new ArrayList<>();
        for (String element : declared.split(",")) {
            String location = element.trim();
            if (!location.isEmpty()) {
                locations.add(location);
            }
        }
        return locations;
    }

    /** Every installed bundle this project built. */
    static List<Bundle> installed(BundleContext context) {
        List<Bundle> ours = new ArrayList<>();
        for (Bundle bundle : context.getBundles()) {
            String location = bundle.getLocation();
            if (location != null && location.startsWith(LOCATION_PREFIX)) {
                ours.add(bundle);
            }
        }
        return ours;
    }

    /** A fragment has no lifecycle of its own; starting one throws. */
    static boolean isFragment(Bundle bundle) {
        return bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null;
    }

    /**
     * The weight of the jars nested inside a bundle. Zero for a bundle whose
     * {@code Bundle-ClassPath} is absent or names only the bundle itself.
     */
    static long embeddedBytes(Bundle bundle) {
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
