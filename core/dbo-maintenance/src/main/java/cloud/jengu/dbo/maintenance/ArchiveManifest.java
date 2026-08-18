package cloud.jengu.dbo.maintenance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What an archive contains, digested — and one root over all of it.
 *
 * <p>The root is computed over the manifest's entries rather than over the
 * archive's bytes, so an archive can be re-packed, re-compressed, or its
 * entries reordered without invalidating what was attested. A signature over
 * a file's bytes attests a file; a signature over this attests the
 * <em>contents</em>, which is what anybody actually cares about.
 *
 * <p>Entries are sorted by name before hashing. Two exports of the same data
 * must produce the same root, and a ZIP's entry order is not a promise
 * anybody made.
 */
public record ArchiveManifest(List<Entry> entries, String root) {

    /** One file in the archive and the digest of its contents. */
    public record Entry(String name, String sha256) {}

    /**
     * The digest list is its own entry, beside the export's descriptive
     * {@code manifest.json} rather than replacing it — that one carries the
     * outbox fence and per-type counts, and is itself content worth
     * attesting, so it appears in this list like everything else.
     */
    public static final String MANIFEST_ENTRY = "digests.json";

    /**
     * Digests every entry of a plain (unsealed) archive except the manifest
     * itself, and computes the root.
     */
    public static ArchiveManifest of(byte[] plainArchive) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plainArchive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (MANIFEST_ENTRY.equals(entry.getName())) {
                    continue; // the digest list cannot digest itself
                }
                digests.put(entry.getName(), hex(sha256(zip.readAllBytes())));
            }
        }
        List<Entry> entries = new ArrayList<>();
        digests.forEach((name, digest) -> entries.add(new Entry(name, digest)));
        return new ArchiveManifest(entries, rootOf(entries));
    }

    /**
     * The root: a digest over every entry's name and digest, in sorted order.
     *
     * <p>A flat hash rather than a tree, deliberately. A Merkle tree buys
     * inclusion proofs — "this file is in that root, and here is the path" —
     * which nothing here needs yet: the verifier holds the whole archive. When
     * a use appears for proving one file without the rest, this becomes a
     * tree and the root's meaning does not change.
     */
    public static String rootOf(List<Entry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Entry entry : entries) {
                digest.update(entry.name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(HexFormat.of().parseHex(entry.sha256()));
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Serialised into the archive so a reader can see what was attested. */
    public String toJson() {
        StringBuilder json = new StringBuilder("{\"root\":\"").append(root).append("\",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"name\":").append(Names.quote(entries.get(i).name()))
                    .append(",\"sha256\":\"").append(entries.get(i).sha256()).append("\"}");
        }
        return json.append("]}").toString();
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }
}
