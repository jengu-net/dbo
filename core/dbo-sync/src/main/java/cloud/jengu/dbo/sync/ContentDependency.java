package cloud.jengu.dbo.sync;

import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A declared content dependency (§6, REQ-DBO-SYNC-DECLARED-ONLY): the name
 * identifies the upstream (one dependency = one direct upstream —
 * REQ-DBO-SYNC-DIRECT-UPSTREAM-ONLY); only the declared types stream.
 * Declarations are configuration (git) in production.
 */
public record ContentDependency(String name, Set<String> declaredTypes,
                                Supplier<Set<String>> manifest) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    /** A dependency that takes every object of the types it declares. */
    public ContentDependency(String name, Set<String> declaredTypes) {
        this(name, declaredTypes, Set.of());
    }

    /** A dependency whose manifest does not move once it is known. */
    public ContentDependency(String name, Set<String> declaredTypes, Set<String> manifest) {
        this(name, declaredTypes, held(manifest));
    }

    private static Supplier<Set<String>> held(Set<String> manifest) {
        Set<String> copy = Set.copyOf(manifest);
        return () -> copy;
    }

    public ContentDependency {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid dependency name: " + name);
        }
        declaredTypes = Set.copyOf(declaredTypes);
        if (declaredTypes.isEmpty()) {
            throw new IllegalArgumentException(name + ": a dependency must declare at least one type");
        }
    }

    /**
     * What this dependency wants, as the upstream is asked for it.
     *
     * <p>The manifest is a set of NAMES and never a query: computed by
     * whoever holds the definitions, because a dependent cannot compute the
     * closure of what it does not yet hold, and then selected by at the
     * upstream. Empty means every object of the declared types, which is what
     * a dependency without a derived filter has always meant.
     *
     * <p><b>Asked for, and not held.</b> A manifest computed once cannot learn
     * what it excluded: the only thing that would tell a dependent about a
     * definition its closure reaches is the feed the manifest is filtering. A
     * tenant in that state is not visibly broken — it holds a face that
     * stopped growing, and goes on judging writes against the version it had
     * when it started. So this is asked every round rather than kept, and
     * whoever answers decides how often it is worth recomputing.
     */
    public cloud.jengu.dbo.core.api.feed.FeedSelection wanted() {
        return new cloud.jengu.dbo.core.api.feed.FeedSelection(declaredTypes, manifest.get());
    }
}
