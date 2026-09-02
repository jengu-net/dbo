package cloud.jengu.dbo.work;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The chain a run's travel and access entries make, rooted in the task.
 *
 * <p>Each link commits to the one before it and the first commits to the
 * task the store minted, so a participant cannot present a journey that
 * never started. The link lives on the entry: there is no separate chain to
 * prune, and a predecessor retention took reads as <em>unchained</em> rather
 * than broken — the distinction {@code VersionChain} already draws for a
 * record's own history.
 *
 * <p>What the chain can say, when the result lands: every link present
 * commits to one that is present or to the root, exactly one link follows
 * each, and the head the result carries is the last of them. A completion
 * whose chain has a hole is refused and told which link. What the chain
 * cannot say is that a link was never made — an intended recipient can open
 * and not report, and nothing commits to a link that does not exist. That
 * limit is accepted rather than hidden.
 */
public final class RunChain {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private RunChain() {}

    /**
     * One link as the trail holds it.
     *
     * @param code      {@code travel} or {@code access}
     * @param previous  the link this one commits to, or the root
     * @param link      this link
     * @param author    who made it — the claimant for travel, the opener for access
     * @param subject   who it was handed to for travel; the reference opened for access
     * @param signature the author's signature over the link, or null where the store made it
     */
    public record Link(String code, String previous, String link, String author, String subject,
            String signature) {}

    /** What verification found: the head, or the link that is missing before a present one. */
    public record Verdict(String head, String missingBefore, boolean unchained, int length) {

        public boolean complete() {
            return missingBefore == null;
        }
    }

    /** The root: the task itself, which the store minted and a participant did not. */
    public static String root(Run run) {
        return digest("dbo:task:" + run.id());
    }

    /** A hop: who the work was handed to, committing to what came before. */
    public static String travelLink(String previous, String runKey, String to) {
        return digest(previous + "\ntravel\n" + runKey + "\n" + to);
    }

    /** An opening: what was opened and by whom, committing to what came before. */
    public static String accessLink(String previous, String runKey, String reference, String by) {
        return digest(previous + "\naccess\n" + runKey + "\n" + reference + "\n" + by);
    }

    /**
     * Follows the links from the root. Order of arrival does not matter:
     * each link names its predecessor, so the chain is walked by pointer
     * rather than by time. A start whose predecessor is neither the root nor
     * present is a pruned one, and the chain reads unchained from there; a
     * link whose predecessor is absent anywhere else is a hole, and the
     * verdict names what is missing.
     */
    public static Verdict verify(Run run, List<Link> links) {
        String root = root(run);
        if (links.isEmpty()) {
            return new Verdict(root, null, false, 0);
        }
        Map<String, Link> byPrevious = new HashMap<>();
        Map<String, Link> byLink = new HashMap<>();
        for (Link link : links) {
            if (byPrevious.put(link.previous(), link) != null) {
                // Two links committing to one predecessor is a fork, and a
                // fork is a chain somebody rewrote: refused as a hole at the
                // point where the two disagree.
                return new Verdict(null, link.previous(), false, 0);
            }
            byLink.put(link.link(), link);
        }
        Link start = byPrevious.get(root);
        boolean unchained = false;
        if (start == null) {
            // Nothing commits to the root: either the earliest links were
            // pruned, or somebody presented a journey that never started.
            // The two differ by whether exactly one start remains whose
            // predecessor is absent — pruning leaves one; a fabricated
            // chain with a missing middle leaves a link committing to
            // something nobody holds, further along.
            List<Link> starts = new ArrayList<>();
            for (Link link : links) {
                if (!byLink.containsKey(link.previous())) {
                    starts.add(link);
                }
            }
            if (starts.size() != 1) {
                return new Verdict(null, starts.isEmpty() ? root : starts.get(1).previous(),
                        false, 0);
            }
            start = starts.get(0);
            unchained = true;
        }
        int length = 0;
        Link at = start;
        String head = root;
        while (at != null) {
            head = at.link();
            length++;
            at = byPrevious.get(head);
        }
        if (length != links.size()) {
            // Links exist that the walk never reached: they commit to
            // something that is not here. Name the first such predecessor.
            for (Link link : links) {
                if (!byLink.containsKey(link.previous()) && link != start
                        && !link.previous().equals(root)) {
                    return new Verdict(null, link.previous(), unchained, length);
                }
            }
            return new Verdict(null, head, unchained, length);
        }
        return new Verdict(head, null, unchained, length);
    }

    /** The head as the trail holds it, or the root when nothing has been recorded. */
    public static Optional<String> head(Run run, List<Link> links) {
        Verdict verdict = verify(run, links);
        return verdict.complete() ? Optional.of(verdict.head()) : Optional.empty();
    }

    private static String digest(String text) {
        try {
            return B64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of the platform", impossible);
        }
    }
}
