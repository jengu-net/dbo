package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.work.RunChain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a zone records of work it carries between tenants: that it passed,
 * from where to where, and nothing of what it was.
 *
 * <p><b>A zone that only carries keeps no run.</b> The work is the asker's
 * and the owner's; a zone on the way holds a segment of the crossing's
 * chain instead, rooted in the crossing the asker's door minted, written
 * through the same path every other entry of the zone's trail is. So a
 * jurisdiction can answer which crossings went through it, and when, without
 * holding a copy of anybody's work to answer it with.
 */
final class ZoneTrail {

    /** The key a crossing's entries are recorded under in a zone it passes. */
    static String keyOf(String crossing) {
        return "crossing:" + crossing;
    }

    private ZoneTrail() {}

    /**
     * The zone received the crossing from one side and handed it to the
     * other: one travel entry, chained after whatever the zone recorded of
     * this crossing before — the way out, when the answer comes back.
     */
    static void passed(cloud.jengu.dbo.policy.PolicyObjectStore zone, String crossing,
            String from, String to) {
        String key = keyOf(crossing);
        List<RunChain.Link> links = TrailLinks.of(zone, key);
        String previous = RunChain.verify(RunChain.crossingRoot(crossing), links).head();
        if (previous == null) {
            throw new IllegalStateException("crossing " + crossing + ": the zone's segment "
                    + "of it does not chain, and a hop is not added to a broken chain");
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("from", from);
        detail.put("to", to);
        detail.put("key", key);
        detail.put("previous", previous);
        detail.put("link", RunChain.travelLink(previous, key, to));
        try (Caller.InRun named = Caller.writingFor(key)) {
            zone.recordCustom("travel", "Crossing", crossing, detail);
        }
    }

    /**
     * The zone heard that the crossing's answer was opened: who opened it and
     * in which tenant, and nothing of what — the owner's references are not
     * the zone's to keep. Recorded once however often it is reported, keyed
     * by the opening as the tenant it happened in recorded it.
     */
    static void opened(cloud.jengu.dbo.policy.PolicyObjectStore zone, String crossing,
            String reader, String where, String reported) {
        String key = keyOf(crossing);
        String previous = RunChain.verify(RunChain.crossingRoot(crossing),
                TrailLinks.of(zone, key)).head();
        if (previous == null) {
            throw new IllegalStateException("crossing " + crossing + ": the zone's segment "
                    + "of it does not chain, and an opening is not added to a broken chain");
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("by", reader);
        detail.put("in", where);
        detail.put("key", key);
        detail.put("previous", previous);
        detail.put("link", RunChain.accessLink(previous, key, "answer", reader));
        try (Caller.InRun named = Caller.writingFor(key)) {
            zone.recordForwarded("access", "Crossing", crossing, detail, null,
                    key + ":opened:" + reported);
        }
    }

    /** The zone's segment of a crossing, as its trail holds it. */
    static List<RunChain.Link> of(ObjectStore zone, String crossing) {
        return TrailLinks.of(zone, keyOf(crossing));
    }
}
