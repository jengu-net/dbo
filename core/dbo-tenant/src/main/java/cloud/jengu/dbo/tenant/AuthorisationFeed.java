package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What a tenant authorised of its register, as each of its places reads it:
 * the one record as it stands now, handed again whenever its version moves.
 *
 * <p><b>The record, not its history, and not the work feed.</b> The record is
 * replaced whole by every authorisation, so the latest version is everything
 * a place acts on, and its history stays where the tenant gave it. A place
 * reading the work feed instead would hold that feed's every run below its
 * position for as long as it stayed away, and one that first read after the
 * feed was pruned would never see an authorisation given before.
 *
 * <p>Each place's position is the version it was last handed, kept in memory.
 * After a restart every place is handed the current version once more, which
 * it already holds and skips.
 */
final class AuthorisationFeed implements ChangeFeed {

    private final Supplier<Optional<StoredObject>> current;
    private final Map<String, Long> handed;

    /**
     * @param current the tenant's record as it stands, or empty where it never
     *                authorised at its door
     * @param handed  the version each place was last handed, by consumer, kept
     *                for as long as the node runs
     */
    AuthorisationFeed(Supplier<Optional<StoredObject>> current, Map<String, Long> handed) {
        this.current = current;
        this.handed = handed;
    }

    @Override
    public FeedChunk<FeedItem> readFor(String consumer, int limit) {
        Optional<StoredObject> record = current.get();
        if (record.isEmpty()) {
            return new FeedChunk<>(List.of(), null, true);
        }
        StoredObject now = record.get();
        String at = String.valueOf(now.versionId());
        if (Long.valueOf(now.versionId()).equals(handed.get(consumer))) {
            return new FeedChunk<>(List.of(), at, true);
        }
        return new FeedChunk<>(List.of(new FeedItem(now.versionId(), now.id(), now.typeName(),
                now.versionId(), ChangeKind.UPDATED, now.lastUpdated(), now.payload(), false,
                now.payloadVersion())), at, true);
    }

    @Override
    public FeedChunk<FeedItem> read(String cursor, int limit) {
        throw new UnsupportedOperationException("a place reads the authorisation from where it "
                + "stands");
    }

    @Override
    public void ack(String consumer, String cursor) {
        if (cursor != null) {
            handed.merge(consumer, Long.parseLong(cursor), Math::max);
        }
    }

    @Override
    public void resetConsumer(String consumer, String cursor) {
        if (cursor == null) {
            handed.remove(consumer);
        } else {
            handed.put(consumer, Long.parseLong(cursor));
        }
    }

    @Override
    public String cursorOf(String consumer) {
        Long at = handed.get(consumer);
        return at == null ? null : String.valueOf(at);
    }

    @Override
    public long lag(String consumer) {
        return current.get().filter(now -> !Long.valueOf(now.versionId())
                .equals(handed.get(consumer))).isPresent() ? 1 : 0;
    }
}
