package cloud.jengu.dbo.core.api.feed;

import java.util.Set;

/**
 * What a consumer wants from a feed, said as a set of names.
 *
 * <p><b>A filter is not a predicate that travels.</b> It is a set of names,
 * computed once and agreed between the two ends: the dependent says which
 * types it operates on and, where its dependency is derived, which canonicals
 * it needs; the upstream selects by name. Nothing is executed on anybody's
 * behalf, which is what keeps a tenant from running another tenant's query
 * while still not moving everything it will discard.
 *
 * <p>Both halves are OPTIONAL and empty means everything. A consumer that
 * names no types takes every type, as every consumer did before this existed;
 * one that names no canonicals takes every object of the types it named.
 *
 * <p><b>Why the canonicals are separate from the types.</b> They narrow
 * different things. A type is what an item IS and every item has one; a
 * canonical is what a definition is identified BY and an ordinary record has
 * none. So the canonicals apply where an item has one and nowhere else — a
 * Patient is not withheld from a dependent because it is absent from a list
 * of definition urls.
 *
 * @param types      the type names to take, or empty for all of them
 * @param canonicals the canonicals to take, for items identified by one, or
 *                   empty to take them all
 */
public record FeedSelection(Set<String> types, Set<String> canonicals) {

    /** Everything, which is what a feed answered before it could be narrowed. */
    public static final FeedSelection EVERYTHING = new FeedSelection(Set.of(), Set.of());

    public FeedSelection {
        types = Set.copyOf(types);
        canonicals = Set.copyOf(canonicals);
    }

    /** The types alone, for a dependency that declares no manifest. */
    public static FeedSelection ofTypes(Set<String> types) {
        return new FeedSelection(types, Set.of());
    }

    /** Whether this narrows anything at all. */
    public boolean everything() {
        return types.isEmpty() && canonicals.isEmpty();
    }
}
