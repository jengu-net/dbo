package cloud.jengu.dbo.definitions;

import cloud.jengu.dbo.core.api.Domains;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A face, cut once and brought up from many times.
 *
 * <p>A tenant coming up on a face reads the whole of what that face publishes
 * through the chain, expands every structure it receives and imports every
 * vocabulary — half a minute of work whose answer is identical for every
 * tenant on that face, and is recomputed by each of them. The answer is a
 * schema, so it can be cut once per release and handed over as bytes instead.
 *
 * <p><b>What travels.</b> Everything in the definitions schema that IS the
 * face: the definition records, their history, their identifiers and edges,
 * and the rows derived from them. What does not travel is the handful of
 * tables describing a tenant's own relationship to a feed — its outbox, its
 * consumers, where its dependencies had got to. Those are about the tenant
 * and not about the face, and a tenant that inherited another's would be
 * standing at a position it never reached.
 *
 * <p><b>Why the manifest is the contract.</b> An image is bytes that look
 * correct whatever they came from. It matches this release or it does not, and
 * nothing about the mismatch shows up as an error: the rows load, the tenant
 * serves, and it answers from a specification that is not the one this release
 * carries — or from elements expanded by an expander that has since been
 * fixed. So the manifest names what it was cut from, it is written last so a
 * half-written image has none, and an image whose manifest disagrees with this
 * release is refused by name rather than loaded and hoped about.
 *
 * <p><b>There is nothing to hide in one.</b> A face is a public specification
 * plus the definitions a zone declares; it carries no person, which is why it
 * may travel as plain bytes while a tenant's archive may not. The operator
 * holds these the way it holds secrets and archives — it schedules the cutting
 * and chooses where they live — and this class is only the two ends.
 */
public final class FaceImage {

    /** The manifest, written last. An image without one is not an image. */
    public static final String MANIFEST_ENTRY = "manifest.json";

    private static final String TABLE_PREFIX = "tables/";

    /**
     * What a tenant's relationship to a feed is written in, and therefore what
     * an image must not carry: where its readers had got to, and where its
     * dependencies stand.
     *
     * <p>The outbox is NOT among them, though it was at first. A consumer row
     * records where somebody READ to, and inheriting one puts a reader
     * somewhere it has never been. The outbox records what a tenant
     * PUBLISHED, and for a face root that is the face itself — so a root
     * brought up from an image without one would hold every definition and
     * offer none of them, and its subscribers would read an empty feed and
     * come up with nothing. Same failure, opposite direction.
     */
    private static final Set<String> NOT_THE_FACE = Set.of(
            Domains.DEFINITIONS + "_consumer",
            Domains.DEFINITIONS + "_sync_origin",
            Domains.DEFINITIONS + "_sync_shadow",
            Domains.DEFINITIONS + "_sync_dlq",
            // The shape marker belongs with them for the same reason the
            // installed-SQL register does: it says which expander took THIS
            // database's rows apart, and a database has one as soon as its
            // store is built — before any face arrives. Carried in an image
            // it would be the first thing already in the way, and the answer
            // it holds is in the manifest anyway, where it is checked before
            // a single row is loaded.
            "definition_shape");

    private FaceImage() {}

    /**
     * What an image says it was cut from — every part of which has to agree
     * before it may be loaded.
     *
     * @param release   the release that cut it
     * @param face      the face code, so an r4 image cannot come up as r5
     * @param faceSql   the fingerprint of the SQL functions of that release,
     *                  because the rows are shaped for the checks that read them
     * @param shape     the definition-row shape, which moves when the expander does
     */
    public record Facts(String release, String face, String faceSql, int shape) {}

    /**
     * An image, by what it holds and where its face stood when it was taken.
     *
     * @param cursor the definitions feed position the image is consistent
     *               with, opaque here: it is minted and read by the feed, and
     *               this only carries it from the one end to the other
     * @param digest over the bytes carried, so two images can be compared
     *               without being opened
     */
    public record Manifest(Facts facts, String cursor, String digest, long rows, Instant cutAt) {}

    /** Accepted, or refused with the reason said in full. */
    public sealed interface Acceptance {
        record Accepted(Manifest manifest, long rows) implements Acceptance {}

        record Refused(String why) implements Acceptance {}
    }

    // ------------------------------------------------------------- cutting

    /**
     * Cuts an image of this database's definitions schema.
     *
     * <p>Taken in one repeatable-read transaction, so the tables are
     * consistent with each other. The caller supplies the feed position and
     * is the one that has to be sure nothing is writing: warmup quiesces the
     * root and drains its queue before asking, which is the only state in
     * which a cursor and a dump mean the same instant.
     */
    public static Manifest cut(DataSource from, Facts facts, String cursor, OutputStream out)
            throws IOException {
        MessageDigest digest = sha256();
        long rows = 0;
        Map<String, String> dumped = new LinkedHashMap<>();
        try (Connection c = from.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                CopyManager copy = new CopyManager(c.unwrap(BaseConnection.class));
                for (String table : carried(c)) {
                    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                    copy.copyOut("COPY " + Domains.schema(Domains.DEFINITIONS) + "."
                            + table + " TO STDOUT WITH (FORMAT csv)", body);
                    byte[] bytes = body.toByteArray();
                    digest.update(table.getBytes(StandardCharsets.UTF_8));
                    digest.update(bytes);
                    rows += countOf(bytes);
                    dumped.put(table, new String(bytes, StandardCharsets.UTF_8));
                }
            } catch (SQLException e) {
                throw new IOException("the face could not be cut", e);
            } finally {
                rollbackQuietly(c);
            }
        } catch (SQLException e) {
            throw new IOException("the face could not be cut", e);
        }

        Manifest manifest = new Manifest(facts, cursor,
                HexFormat.of().formatHex(digest.digest()), rows, Instant.now());
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> table : dumped.entrySet()) {
                zip.putNextEntry(new ZipEntry(TABLE_PREFIX + table.getKey() + ".csv"));
                zip.write(table.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            // Last, deliberately: an image cut by a job that died halfway
            // through has no manifest, and an image with no manifest is
            // refused rather than half-loaded.
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(json(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return manifest;
    }

    // ----------------------------------------------------------- accepting

    /**
     * Loads an image into this database, or says why it did not.
     *
     * <p>Refusal is a result rather than a failure because the caller has
     * somewhere to go: a tenant whose image does not match this release comes
     * up the way tenants came up before there were images. What it may not do
     * is come up from it anyway.
     */
    public static Acceptance accept(DataSource into, Facts expected, InputStream image)
            throws IOException {
        Map<String, byte[]> tables = new LinkedHashMap<>();
        Manifest manifest = null;
        try (ZipInputStream zip = new ZipInputStream(image)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] body = zip.readAllBytes();
                if (MANIFEST_ENTRY.equals(entry.getName())) {
                    manifest = manifestOf(new String(body, StandardCharsets.UTF_8));
                } else if (entry.getName().startsWith(TABLE_PREFIX)) {
                    tables.put(tableNameOf(entry.getName()), body);
                }
            }
        }
        if (manifest == null) {
            return new Acceptance.Refused("the image carries no manifest, so it is either "
                    + "half-written or not an image; nothing was loaded");
        }
        String disagreement = disagreement(expected, manifest.facts());
        if (disagreement != null) {
            return new Acceptance.Refused(disagreement);
        }

        long loaded = 0;
        try (Connection c = into.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (String table : tables.keySet()) {
                    String missing = refuseIfAbsentOrOccupied(c, table);
                    if (missing != null) {
                        c.rollback();
                        return new Acceptance.Refused(missing);
                    }
                }
                CopyManager copy = new CopyManager(c.unwrap(BaseConnection.class));
                for (Map.Entry<String, byte[]> table : tables.entrySet()) {
                    loaded += copy.copyIn("COPY " + Domains.schema(Domains.DEFINITIONS) + "."
                                    + table.getKey() + " FROM STDIN WITH (FORMAT csv)",
                            new java.io.ByteArrayInputStream(table.getValue()));
                }
                realignTheOutbox(c);
                c.commit();
            } catch (SQLException e) {
                rollbackQuietly(c);
                throw new IOException("the image could not be loaded", e);
            }
        } catch (SQLException e) {
            throw new IOException("the image could not be loaded", e);
        }
        return new Acceptance.Accepted(manifest, loaded);
    }

    /**
     * Puts the outbox's sequence past what was loaded.
     *
     * <p>Rows arrive carrying the numbers they had where they were cut, and
     * the sequence behind the column knows nothing about them: left alone it
     * hands out numbers that already exist, and the first definition this
     * tenant publishes collides with one of its own.
     */
    private static void realignTheOutbox(Connection c) throws SQLException {
        String outbox = Domains.tables(Domains.DEFINITIONS) + "_outbox";
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT setval(pg_get_serial_sequence('" + outbox + "', 'seq'),"
                        + " COALESCE((SELECT max(seq) FROM " + outbox + "), 0) + 1, false)")) {
            ps.execute();
        }
    }

    /** Which part of an image disagrees with this release, said in full. */
    private static String disagreement(Facts expected, Facts carried) {
        if (!expected.face().equals(carried.face())) {
            return "the image is of face '" + carried.face() + "' and this tenant is '"
                    + expected.face() + "'";
        }
        if (expected.shape() != carried.shape()) {
            return "the image's definition rows are shape " + carried.shape()
                    + " and this release expands to shape " + expected.shape()
                    + ", so its rows were taken apart by an expander this one is not";
        }
        if (!expected.faceSql().equals(carried.faceSql())) {
            return "the image was cut for face SQL " + shortly(carried.faceSql())
                    + " and this release carries " + shortly(expected.faceSql())
                    + ", so the checks that would read these rows are not the ones "
                    + "they were shaped for";
        }
        if (!expected.release().equals(carried.release())) {
            return "the image was cut by release " + carried.release()
                    + " and this is " + expected.release();
        }
        return null;
    }

    /**
     * An image is loaded into a schema that is set up and empty.
     *
     * <p>A table that is not there yet means the store has not finished
     * building the schema, and one that already has rows means something got
     * there first — loading over either would double rows or lose them with
     * no error at all.
     */
    private static String refuseIfAbsentOrOccupied(Connection c, String table)
            throws SQLException {
        String qualified = Domains.schema(Domains.DEFINITIONS) + "." + table;
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?)::text")) {
            ps.setString(1, qualified);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getString(1) == null) {
                    return "the image carries " + qualified + " and this database has no such "
                            + "table: its definitions schema is not set up yet";
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM " + qualified + ")");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1)
                    ? "the image would be loaded over " + qualified + ", which already holds "
                            + "rows; an image is brought up from, not merged into"
                    : null;
        }
    }

    // -------------------------------------------------------------- tables

    /**
     * The schema's tables, discovered rather than listed — minus the ones
     * that are about this tenant rather than about the face.
     *
     * <p>Discovered for the reason the backup discovers them: a list is a
     * second description of the database, and it stops being true without
     * anything failing. A table added to the schema travels by default, which
     * is the safe direction: carrying one table too many costs bytes, and
     * carrying one too few makes an image that comes up short and says
     * nothing.
     */
    private static List<String> carried(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                ORDER BY table_name""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    if (!NOT_THE_FACE.contains(table)) {
                        tables.add(table);
                    }
                }
            }
        }
        return tables;
    }

    private static String tableNameOf(String entry) {
        String name = entry.substring(TABLE_PREFIX.length());
        String table = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
        if (!table.matches("[a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("the image names a table it should not: " + table);
        }
        return table;
    }

    // ------------------------------------------------------------ manifest

    private static String json(Manifest m) {
        return ("{\"release\":\"%s\",\"face\":\"%s\",\"faceSql\":\"%s\",\"shape\":%d,"
                + "\"cursor\":\"%s\",\"digest\":\"%s\",\"rows\":%d,\"cutAt\":\"%s\"}")
                .formatted(escaped(m.facts().release()), escaped(m.facts().face()),
                        escaped(m.facts().faceSql()), m.facts().shape(),
                        escaped(m.cursor() == null ? "" : m.cursor()), m.digest(), m.rows(),
                        m.cutAt());
    }

    private static Manifest manifestOf(String json) {
        String cursor = string(json, "cursor");
        return new Manifest(
                new Facts(string(json, "release"), string(json, "face"),
                        string(json, "faceSql"), (int) number(json, "shape")),
                cursor.isEmpty() ? null : cursor,
                string(json, "digest"), number(json, "rows"),
                Instant.parse(string(json, "cutAt")));
    }

    private static String string(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            throw new IllegalArgumentException("the manifest does not say " + field);
        }
        int from = at + needle.length();
        return json.substring(from, json.indexOf('"', from));
    }

    private static long number(String json, String field) {
        String needle = "\"" + field + "\":";
        int at = json.indexOf(needle);
        if (at < 0) {
            throw new IllegalArgumentException("the manifest does not say " + field);
        }
        int from = at + needle.length();
        int to = from;
        while (to < json.length() && (Character.isDigit(json.charAt(to)) || json.charAt(to) == '-')) {
            to++;
        }
        return Long.parseLong(json.substring(from, to));
    }

    private static String escaped(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String shortly(String fingerprint) {
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }

    // ------------------------------------------------------------ plumbing

    private static long countOf(byte[] csv) {
        long lines = 0;
        for (byte b : csv) {
            if (b == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the caller is already reporting whatever went wrong
        }
    }
}
