package cloud.jengu.dbo.runner.transport;

import java.time.Instant;
import java.util.List;

/**
 * A place's trail, as the tenant takes it from that place: the entries the
 * place recorded, in the order it recorded them, from where the tenant says
 * it stopped.
 *
 * <p><b>The tenant keeps the position.</b> A site that was away, or was
 * replaced, asks where it stands and reads its own trail from there; it keeps
 * nothing of its own about what it has handed up. Handing an entry twice is
 * harmless, because the tenant records each entry under the place's own
 * identity for it and finds the first when the second arrives — so a place
 * that died between handing a batch and hearing back hands it again.
 *
 * <p><b>Whose trail it is, the tenant says.</b> An entry is filed as the
 * place's by the credential that handed it, never by anything the entries
 * say about themselves.
 */
public interface Trail {

    /**
     * One entry, as the place recorded it.
     *
     * @param id         its id at the place, which the tenant files it under
     * @param version    the version it stands at there
     * @param recordedAt when the place recorded it
     * @param payload    the entry as the place wrote it
     */
    record Entry(String id, long version, Instant recordedAt, byte[] payload) {}

    /**
     * Where the tenant holds a place's trail.
     *
     * @param feed    which trail the position is in. A place whose database was
     *                rebuilt has a trail that started over, and a position in
     *                the old one means nothing in the new
     * @param through the position after the last entry taken, or null for
     *                none taken yet
     */
    record Position(String feed, String through) {}

    /** Where the tenant holds this place's trail, or null when it has taken nothing. */
    Position position();

    /**
     * Hands the tenant these entries, which run through {@code through} in
     * the place's trail.
     *
     * @return where the tenant now holds this place's trail
     */
    Position handed(String feed, List<Entry> entries, String through);
}
