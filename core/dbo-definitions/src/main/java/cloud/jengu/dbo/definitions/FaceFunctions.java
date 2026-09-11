package cloud.jengu.dbo.definitions;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * The functions a tenant's database answers with, installed from the release
 * that owns them (REQ-DBO-VER-THE-FACE-SQL-SHIPS-WITH-THE-RELEASE).
 *
 * <p><b>Code by release, data by chain, and never the other way.</b> The rows
 * these functions read arrive through replication, restore and the face; the
 * functions themselves arrive only here, from files inside this bundle, put
 * there by the dbo version running. A function that could arrive through a
 * feed would be a way to make one tenant run something by writing to another
 * tenant's stream, and this store is not going to have one.
 *
 * <p><b>Plain SQL, no extension.</b> Faster things exist — PL/Rust, C — and
 * every one of them has to be installed on the server, which an appliance or
 * a managed Postgres may simply not permit. What is here runs wherever the
 * store runs.
 *
 * <p><b>Versioned by what it is, not by what it claims.</b> The installer
 * fingerprints the scripts it carries and records that; a release whose SQL
 * did not change reinstalls nothing, and one whose SQL did is applied whole.
 * A release number would say which build wrote the functions and not whether
 * they are the functions that build carries — after a downgrade those are
 * different questions.
 */
public final class FaceFunctions {

    /**
     * In order, because later ones use earlier ones. Listed rather than
     * discovered: a bundle's resources cannot be enumerated portably, and an
     * order that emerged from a directory listing is an order nobody chose.
     */
    private static final List<String> SCRIPTS = List.of(
            "/sql/001-schema.sql",
            "/sql/002-located.sql",
            "/sql/003-instances.sql",
            "/sql/004-cardinality.sql",
            "/sql/005-terminology.sql",
            "/sql/006-values.sql",
            "/sql/007-bindings.sql",
            "/sql/009-references.sql",
            "/sql/011-invariants.sql",
            "/sql/012-validate.sql");

    /**
     * What the scripts read, and what therefore has to be there before they
     * are created.
     *
     * <p>A SQL function is checked against the catalogue when it is created,
     * so a missing table is a parse error pointing at a line of shipped SQL
     * rather than at the arrangement that is actually wrong. These tables
     * belong to other parts of the store — the expanded definitions to this
     * module, the terminology to its own — and a tenant's bring-up creates
     * them before it gets here. Naming them turns "relation does not exist"
     * into a sentence about what was brought up in what order.
     */
    private static final List<String> READS = List.of(
            "state.definition_element",
            "state.definition_invariant",
            "state.term_valueset",
            "state.term_system",
            "state.term_concept");

    private FaceFunctions() {}

    /**
     * Put this release's functions in place, if they are not already.
     *
     * @return the fingerprint now installed
     */
    public static String install(DataSource dataSource) {
        List<String> scripts = SCRIPTS.stream().map(FaceFunctions::read).toList();
        String fingerprint = fingerprintOf(scripts);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ensureRegister(c);
                if (fingerprint.equals(installed(c))) {
                    c.commit();
                    return fingerprint;
                }
                refuseIfWhatTheyReadIsAbsent(c);
                for (String script : scripts) {
                    try (PreparedStatement ps = c.prepareStatement(script)) {
                        ps.execute();
                    }
                }
                record(c, fingerprint);
                c.commit();
                return fingerprint;
            } catch (Throwable t) {
                c.rollback();
                throw t;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "installing this release's database functions failed", e);
        }
    }

    /** What is installed here, or null where nothing is. */
    public static String installedIn(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            ensureRegister(c);
            return installed(c);
        } catch (SQLException e) {
            throw new IllegalStateException("reading the installed functions failed", e);
        }
    }

    private static void refuseIfWhatTheyReadIsAbsent(Connection c) throws SQLException {
        List<String> absent = new java.util.ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NULL")) {
            for (String table : READS) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        absent.add(table);
                    }
                }
            }
        }
        if (!absent.isEmpty()) {
            throw new IllegalStateException("this release's functions read " + absent
                    + ", which this tenant's database does not have yet — they are installed "
                    + "after the tables they read, and something brought them up in the "
                    + "other order");
        }
    }

    // ------------------------------------------------------------- register

    private static void ensureRegister(Connection c) throws SQLException {
        for (String ddl : List.of(
                "CREATE SCHEMA IF NOT EXISTS state",
                """
                CREATE TABLE IF NOT EXISTS state.face_sql (
                  only_row     int PRIMARY KEY DEFAULT 1 CHECK (only_row = 1),
                  fingerprint  text        NOT NULL,
                  release      text,
                  installed_at timestamptz NOT NULL DEFAULT now()
                )""")) {
            try (PreparedStatement ps = c.prepareStatement(ddl)) {
                ps.execute();
            }
        }
    }

    private static String installed(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT fingerprint FROM state.face_sql WHERE only_row = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void record(Connection c, String fingerprint) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO state.face_sql (only_row, fingerprint, release, installed_at)
                VALUES (1, ?, ?, now())
                ON CONFLICT (only_row) DO UPDATE SET fingerprint = EXCLUDED.fingerprint,
                  release = EXCLUDED.release, installed_at = now()""")) {
            ps.setString(1, fingerprint);
            ps.setString(2, release());
            ps.executeUpdate();
        }
    }

    /** The build these functions shipped in, where the jar says — a label. */
    private static String release() {
        Package where = FaceFunctions.class.getPackage();
        String stated = where == null ? null : where.getImplementationVersion();
        return stated != null ? stated : "unstated";
    }

    // -------------------------------------------------------------- scripts

    private static String read(String resource) {
        try (InputStream in = FaceFunctions.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("the release is missing " + resource
                        + ", so a tenant would be brought up without the functions it "
                        + "answers with");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("reading " + resource + " failed", e);
        }
    }

    private static String fingerprintOf(List<String> scripts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String script : scripts) {
                digest.update(script.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 32);
        } catch (NoSuchAlgorithmException never) {
            throw new IllegalStateException("SHA-256 is absent from this JVM", never);
        }
    }
}
