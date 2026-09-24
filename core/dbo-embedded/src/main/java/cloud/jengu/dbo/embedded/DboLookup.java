package cloud.jengu.dbo.embedded;

import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the container is offering right now.
 *
 * <p>Per-tenant services appear when a tenant comes up and are retracted when
 * it goes: its store, its feeds, its questions, its lane, its authority. A
 * reference resolved once and kept is a reference to a service that may have
 * been withdrawn an hour ago — the same mistake the sample's runner avoids by
 * taking a supplier for its token rather than a string.
 *
 * <p>So there is no caching here and nothing to refresh. Every call asks the
 * registry, and an assembly's own beans are the vocabulary an application
 * reads it through.
 */
public final class DboLookup {

    private final EmbeddedRuntime runtime;

    DboLookup(EmbeddedRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * The one service of this type matching the filter, if there is one.
     *
     * <p>Empty rather than null, and empty rather than an exception: a tenant
     * that is not serving is an ordinary answer to "is it serving", and the
     * assemblies turn it into a refusal that names the tenant where a caller
     * asked for something that needs one.
     */
    public <T> Optional<T> one(Class<T> type, String filter) {
        List<T> found = all(type, filter);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Every service of this type matching the filter. */
    public <T> List<T> all(Class<T> type, String filter) {
        try {
            List<T> found = new ArrayList<>();
            for (ServiceReference<T> ref : runtime.context().getServiceReferences(type, filter)) {
                T service = runtime.context().getService(ref);
                if (service != null) {
                    found.add(service);
                }
            }
            return found;
        } catch (InvalidSyntaxException notAFilter) {
            throw new IllegalArgumentException(filter + " is not a filter the registry can read",
                    notAFilter);
        }
    }

    /** Everything of this type, whichever tenant it belongs to. */
    public <T> List<T> all(Class<T> type) {
        return all(type, null);
    }

    /**
     * The distinct values of one property across the services of a type.
     *
     * <p>How an assembly asks which tenants are serving without holding a
     * list of its own. The registry already knows — every per-tenant service
     * is published with the tenant's code on it — and a second list kept
     * beside it would be right until a tenant came up.
     */
    public <T> List<String> valuesOf(Class<T> type, String property) {
        try {
            List<String> found = new ArrayList<>();
            for (ServiceReference<T> ref : runtime.context().getServiceReferences(type, null)) {
                Object value = ref.getProperty(property);
                if (value != null && !found.contains(value.toString())) {
                    found.add(value.toString());
                }
            }
            java.util.Collections.sort(found);
            return found;
        } catch (InvalidSyntaxException never) {
            throw new IllegalStateException("no filter was given, so none can be invalid", never);
        }
    }
}
