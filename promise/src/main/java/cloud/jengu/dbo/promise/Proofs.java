package cloud.jengu.dbo.promise;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The citation index: which sites prove which promises.
 *
 * <p>Written at each product's own compile time by {@link CatalogueProcessor}
 * (REQ-DBO-PRM-PROOFS-INDEXED-AT-COMPILE-TIME) — one line per site,
 * {@code binary.Class#method=enum.Class:CONSTANT,CONSTANT} — and read here by
 * resolving each named constant against the loaded enum, so a citation that
 * outlives its declaration fails the load rather than reporting quietly.
 */
public final class Proofs {

    /** Where the processor writes and this reader reads. */
    public static final String INDEX = "META-INF/promise/proofs";

    private final Map<String, Set<String>> sitesByCode = new LinkedHashMap<>();

    private Proofs() {
    }

    /** Every citation registered on {@code loader}'s classpath. */
    public static Proofs load(ClassLoader loader) {
        Proofs proofs = new Proofs();
        try {
            Enumeration<URL> indexes = loader.getResources(INDEX);
            while (indexes.hasMoreElements()) {
                URL index = indexes.nextElement();
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(index.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (!line.isBlank()) {
                            proofs.take(loader, line.strip(), index);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + INDEX, e);
        }
        return proofs;
    }

    /** The explicit form, for tests: {@code site} cites {@code promises}. */
    public static Proofs of(Map<String, List<Promise>> citations) {
        Proofs proofs = new Proofs();
        citations.forEach((site, promises) -> promises.forEach(promise ->
                proofs.sitesByCode.computeIfAbsent(promise.code(), c -> new LinkedHashSet<>())
                        .add(site)));
        return proofs;
    }

    /** The sites citing {@code promise}; empty is the answer "nobody". */
    public Set<String> citing(Promise promise) {
        return Set.copyOf(sitesByCode.getOrDefault(promise.code(), Set.of()));
    }

    public boolean cited(Promise promise) {
        return sitesByCode.containsKey(promise.code());
    }

    private void take(ClassLoader loader, String line, URL index) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':', eq);
        if (eq < 0 || colon < 0) {
            throw new IllegalStateException(index + " carries an unreadable line: " + line);
        }
        String site = line.substring(0, eq);
        String enumName = line.substring(eq + 1, colon);
        Class<?> catalogue;
        try {
            catalogue = Class.forName(enumName, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(index + " cites '" + enumName
                    + "', which this classpath does not carry — a stale index is a build "
                    + "defect, not a skippable line", e);
        }
        for (String constant : line.substring(colon + 1).split(",")) {
            Object[] values = catalogue.getEnumConstants();
            Promise found = null;
            for (Object value : values) {
                if (value instanceof Promise promise
                        && ((Enum<?>) value).name().equals(constant.strip())) {
                    found = promise;
                }
            }
            if (found == null) {
                throw new IllegalStateException(index + " cites " + enumName + "."
                        + constant + ", which the enum does not declare");
            }
            sitesByCode.computeIfAbsent(found.code(), c -> new LinkedHashSet<>()).add(site);
        }
    }
}
