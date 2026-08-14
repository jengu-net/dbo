package cloud.jengu.dbo.postgres;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque cursor encoding. Consumers hold cursors; only this class reads
 * them. Format (internal, versioned): {@code v1|<kind>|<field>|<field>…}.
 */
final class Cursors {

    private Cursors() {}

    /** dbo#25: feed cursors are (xact_id, seq) — xid-major commit fencing. */
    record FeedCursor(long xid, long seq) {}

    static String encodeFeed(long xid, long seq) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("x" + xid + ":" + seq).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    static FeedCursor decodeFeed(String cursor) {
        String raw = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                java.nio.charset.StandardCharsets.US_ASCII);
        if (raw.startsWith("x") && raw.contains(":")) {
            int colon = raw.indexOf(':');
            return new FeedCursor(Long.parseLong(raw, 1, colon, 10),
                    Long.parseLong(raw.substring(colon + 1)));
        }
        // a pre-dbo#25 cursor carried only the seq: resume from xid 0 —
        // at-least-once redelivery, never loss
        return new FeedCursor(0, decodeSeq(cursor));
    }

    static String encodeSeq(long seq) {
        return encode("v1|s|" + seq);
    }

    static long decodeSeq(String cursor) {
        String[] parts = decode(cursor);
        require(parts.length == 3 && "s".equals(parts[1]), cursor);
        return Long.parseLong(parts[2]);
    }

    static String encodeKeyset(String sortValue, String id) {
        return encode("v1|k|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sortValue.getBytes(StandardCharsets.UTF_8)) + "|" + id);
    }

    /** [sortValue, id] */
    static String[] decodeKeyset(String cursor) {
        String[] parts = decode(cursor);
        require(parts.length == 4 && "k".equals(parts[1]), cursor);
        String sortValue = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
        return new String[] {sortValue, parts[3]};
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            require(parts.length >= 2 && "v1".equals(parts[0]), cursor);
            return parts;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }

    private static void require(boolean ok, String cursor) {
        if (!ok) {
            throw new IllegalArgumentException("invalid cursor: " + cursor);
        }
    }
}
