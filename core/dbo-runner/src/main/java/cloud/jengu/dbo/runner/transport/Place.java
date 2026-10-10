package cloud.jengu.dbo.runner.transport;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.face.GrainCodec;

import java.util.Set;

/**
 * What a tenant replicates to a second place of itself, as the tenant serves
 * it: its two feeds, the types each may be read for, and the grain that
 * turns what it stores into what travels.
 *
 * <p><b>Bounded here, not by the asker.</b> A place reads the tenant's
 * definitions and the records it takes from upstream. What the tenant
 * authors travels as work and never on this feed, so a place asking for a
 * type it may not read is answered with nothing of it, whatever its
 * credential says.
 *
 * @param records         the tenant's records feed
 * @param definitions     the tenant's definitions feed
 * @param readableRecords the record types a place may read: those the tenant takes from upstream
 * @param grain           what reassembles a type the tenant stores in parts, or null
 */
public record Place(ChangeFeed records, ChangeFeed definitions, Set<String> readableRecords,
        GrainCodec grain) {

    /** The records feed, by name on the wire. */
    public static final String RECORDS = "records";
    /** The definitions feed, by name on the wire. */
    public static final String DEFINITIONS = "definitions";

    public Place {
        readableRecords = Set.copyOf(readableRecords);
    }

    /**
     * The name a participant's position is kept under, in the feed it reads.
     * One per participant and feed: a place is the participant, and two of
     * them never share a position.
     */
    public static String consumerOf(String participant, String domain) {
        return "place." + participant + "." + domain;
    }
}
