package cloud.jengu.dbo.tenant;

import java.time.Instant;
import java.util.Optional;

/**
 * One change, as an observer learns of it.
 *
 * <p>Deliberately not the feed's own item. A {@code FeedItem} carries the
 * exact version's payload, and handing that to an observer is the surface
 * <a href="../../../../../../../../../docs/arc42-008-crosscutting/reaching-the-data/README.md">reaching
 * the data</a> names as the one that quietly becomes general read access:
 * every integrator takes the feed instead of the data plane and the boundary
 * is decorative.
 *
 * <p><b>So a feed says what changed and not what it says.</b> The type, the
 * identity, the version, when, and whether it was a deletion — enough to
 * notice, which is what a feed is for. A consumer that needs the record
 * performs a step and reads it in the run context, where authorisation,
 * purpose and accountability arrive together as they do for everybody else.
 *
 * <p>Declaring the types instead was the obvious repair and is refused in the
 * same document: a declaration of types with no anchor is type-level access
 * control wearing a step's clothing. A feed cannot carry an anchor, because a
 * feed is not <em>about</em> anything — it is everything, in order.
 *
 * @param content the payload, present only for a domain that is the
 *                machinery's own bookkeeping. Empty for a tenant's records
 *                and for the trail, and the emptiness is the rule rather than
 *                a change that happened to carry nothing — a deletion says so
 *                with {@link #deleted()}.
 */
public record Change(long seq, String typeName, String objectId, long versionId,
        Instant committedAt, boolean deleted, Optional<byte[]> content) {

    public Change {
        content = content == null ? Optional.empty() : content;
    }

    /** What an observer of a tenant's records or its trail is told. */
    static Change withoutContent(cloud.jengu.dbo.core.api.feed.FeedItem item) {
        return new Change(item.seq(), item.typeName(), item.objectId(), item.versionId(),
                item.committedAt(), item.deleted(), Optional.empty());
    }

    /** The same, for a domain the five reasons already admit as direct. */
    static Change withContent(cloud.jengu.dbo.core.api.feed.FeedItem item) {
        return new Change(item.seq(), item.typeName(), item.objectId(), item.versionId(),
                item.committedAt(), item.deleted(),
                Optional.ofNullable(item.payload()));
    }
}
