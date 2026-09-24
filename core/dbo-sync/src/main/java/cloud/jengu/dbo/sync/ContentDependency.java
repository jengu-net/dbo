package cloud.jengu.dbo.sync;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A declared content dependency (§6, REQ-DBO-SYNC-DECLARED-ONLY): the name
 * identifies the upstream (one dependency = one direct upstream —
 * REQ-DBO-SYNC-DIRECT-UPSTREAM-ONLY); only the declared types stream.
 * Declarations are configuration (git) in production.
 */
public record ContentDependency(String name, Set<String> declaredTypes) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    public ContentDependency {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid dependency name: " + name);
        }
        declaredTypes = Set.copyOf(declaredTypes);
        if (declaredTypes.isEmpty()) {
            throw new IllegalArgumentException(name + ": a dependency must declare at least one type");
        }
    }
}
