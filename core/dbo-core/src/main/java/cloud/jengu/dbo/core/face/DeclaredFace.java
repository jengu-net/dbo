package cloud.jengu.dbo.core.face;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link DomainFace} built by declaring capabilities one at a time.
 *
 * <p>Keeps a face's definition to a list of what it provides, so adding a
 * capability is a line rather than a new interface to implement — which is the
 * property that stops the contract from becoming the god-interface it exists
 * to avoid.
 */
public final class DeclaredFace implements DomainFace {

    private final String name;
    private final Map<Class<?>, Object> provided;

    private DeclaredFace(String name, Map<Class<?>, Object> provided) {
        this.name = name;
        this.provided = Map.copyOf(provided);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<Class<?>> capabilities() {
        return provided.keySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> capability(Class<T> type) {
        return Optional.ofNullable((T) provided.get(type));
    }

    public static final class Builder {
        private final String name;
        private final Map<Class<?>, Object> provided = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        /** Declares one capability. Declaring the same type twice is a mistake, not a merge. */
        public <T> Builder providing(Class<T> type, T implementation) {
            if (provided.putIfAbsent(type, implementation) != null) {
                throw new IllegalArgumentException(
                        name + " declares " + type.getSimpleName() + " twice — one of them is "
                                + "unreachable, and which one would depend on ordering");
            }
            return this;
        }

        public DomainFace build() {
            return new DeclaredFace(name, provided);
        }
    }
}
