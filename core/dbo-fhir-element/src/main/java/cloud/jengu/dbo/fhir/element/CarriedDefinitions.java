package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.validation.ValidatorUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The definitions this face carries, and the only place it gets them from
 * (REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE).
 *
 * <p>A version's shapes arrive as a package, and the obvious way to load one
 * is to name it and let the toolchain fetch it. A store must not: bringing a
 * tenant up would make a network call, write into a cache outside the store's
 * own state, and depend on what a registry served that day. So the packages
 * are pinned and digest-checked at build time and embedded in this bundle, and
 * a version whose packages are not carried is <b>refused rather than
 * fetched</b> — the failure a caller gets is the one they can act on.
 *
 * <p>What is carried is written by the build into {@code definitions/index},
 * so nothing here names a package or a version: pinning them in two places is
 * how a bundle comes to carry one thing and load another.
 */
public final class CarriedDefinitions {

    /** Where the build put them, inside this bundle. */
    private static final String INDEX = "/definitions/index";

    private CarriedDefinitions() {
    }

    /** One carried package: which version it belongs to, and what it is. */
    public record Carried(String fhirVersion, String name, String version, String file) {

        /** {@code hl7.fhir.r6.core#6.0.0-ballot5} — how a package is named everywhere else. */
        public String id() {
            return name + "#" + version;
        }
    }

    /** Everything this bundle carries, in the order the build pinned it. */
    public static List<Carried> carried() {
        InputStream index = CarriedDefinitions.class.getResourceAsStream(INDEX);
        if (index == null) {
            throw new IllegalStateException("this bundle carries no definitions at all — "
                    + "the build's fetch step did not run, and no version can be served");
        }
        List<Carried> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(index, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|");
                out.add(new Carried(parts[0], parts[1], parts[2], parts[3]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the carried definitions index", e);
        }
        return List.copyOf(out);
    }

    /** Which versions this bundle can serve, for a refusal that says what would work. */
    public static Set<String> versions() {
        Set<String> versions = new LinkedHashSet<>();
        carried().forEach(c -> versions.add(c.fhirVersion()));
        return versions;
    }

    /** What is carried for one version — its core package first, then the rest. */
    public static List<Carried> forVersion(String fhirVersion) {
        List<Carried> packages = carried().stream()
                .filter(c -> c.fhirVersion().equals(fhirVersion))
                .toList();
        if (packages.isEmpty()) {
            throw new NotCarried(fhirVersion, versions());
        }
        return packages;
    }

    /**
     * A worker context holding one version's definitions, built from the
     * bundle's own bytes.
     *
     * <p>The loader is chosen by version rather than assumed: R6 definitions
     * are read by an R6-aware loader, and handing them to the wrong one is how
     * a package loads and then means something else.
     */
    public static SimpleWorkerContext contextFor(String fhirVersion) {
        List<Carried> packages = forVersion(fhirVersion);
        try {
            SimpleWorkerContext context = null;
            for (Carried carried : packages) {
                NpmPackage npm = NpmPackage.fromPackage(open(carried));
                if (context == null) {
                    context = new SimpleWorkerContext.SimpleWorkerContextBuilder()
                            .withAllowLoadingDuplicates(true)
                            .fromPackage(npm, ValidatorUtils.loaderForVersion(npm.fhirVersion()), true);
                } else {
                    context.loadFromPackage(npm,
                            ValidatorUtils.loaderForVersion(npm.fhirVersion()));
                }
            }
            return context;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "cannot read the definitions carried for " + fhirVersion, e);
        }
    }

    /** The package's bytes, from this bundle and nowhere else. */
    private static InputStream open(Carried carried) {
        InputStream bytes = CarriedDefinitions.class
                .getResourceAsStream("/definitions/" + carried.file());
        if (bytes == null) {
            throw new IllegalStateException(carried.id() + " is indexed but not carried — "
                    + "the index and the packages come from one build step, so this means "
                    + "the bundle was assembled from two");
        }
        return bytes;
    }

    /** A version whose definitions this bundle does not carry. */
    public static class NotCarried extends IllegalArgumentException {
        public NotCarried(String fhirVersion, Set<String> carried) {
            super("no definitions are carried for '" + fhirVersion + "' — this face carries "
                    + (carried.isEmpty() ? "none" : String.join(", ", carried))
                    + ". Packages are pinned and embedded at build time, never fetched at "
                    + "bring-up, so this is a build to change rather than a download to wait for.");
        }
    }
}
