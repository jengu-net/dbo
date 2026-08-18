package cloud.jengu.dbo.maintenance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Whether any record points at something that is not there.
 *
 * <p>Nothing validates references on write — there are no foreign keys between
 * objects, deliberately, because clinical data has genuine reference cycles and
 * an import that had to satisfy them in order would have no valid order to
 * choose. The cost of that choice is that a dangling reference can be created
 * in ordinary operation and nothing notices. This is the report that notices.
 *
 * <p>It is <b>standing and re-runnable</b>, not an import gate that happens to
 * be reusable. An operator who accepted twelve loose ends on Monday can ask
 * again on Friday and get a smaller number without having done anything,
 * because a reference resolves by itself the moment its target arrives.
 *
 * <p>The arithmetic runs <b>in the database</b>. Loose ends are
 * {@code required − present}, which is set subtraction; doing it in the JVM
 * would mean holding every reference of a large tenant in memory, and that
 * mistake has already been made twice here, in the export and then the import.
 */
public final class ReferenceClosure {

    private ReferenceClosure() {
    }

    /** A record pointing at something absent, named from both ends. */
    public record LooseEnd(String ownerType, String ownerId, String refType,
                           String targetType, String targetId) {

        @Override
        public String toString() {
            return ownerType + "/" + ownerId + " —" + refType + "→ "
                    + targetType + "/" + targetId;
        }
    }

    /**
     * What the check found.
     *
     * <p>Absence comes in two kinds and conflating them is the failure mode
     * that matters. A reference to a vocabulary the recipient licenses for
     * themselves is <b>absent by design</b>; a reference to a record that
     * should be here and is not is <b>absent by accident</b>. Reporting the
     * first as damage fills the report with expected findings and teaches its
     * reader to stop looking, which costs more than the check was worth.
     */
    public record Report(long referencesChecked, List<LooseEnd> looseEnds,
                         List<LooseEnd> absentByDesign) {

        public Report {
            looseEnds = List.copyOf(looseEnds);
            absentByDesign = List.copyOf(absentByDesign);
        }

        /** Whether every reference that should resolve here does. */
        public boolean isWhole() {
            return looseEnds.isEmpty();
        }

        public String describe() {
            if (isWhole()) {
                return referencesChecked + " references, all resolved"
                        + (absentByDesign.isEmpty() ? ""
                        : " (" + absentByDesign.size() + " resolved elsewhere by design)");
            }
            StringBuilder text = new StringBuilder(looseEnds.size() + " of " + referencesChecked
                    + " references point at something that is not there:");
            for (LooseEnd end : looseEnds) {
                text.append("\n  ").append(end);
            }
            return text.toString();
        }
    }

    /**
     * Checks every reference held by a live object in the domain.
     *
     * @param resolvedElsewhere target types whose absence is expected — a
     *                          vocabulary the archive deliberately did not
     *                          redistribute, resolved by the reader under
     *                          their own licence. When terminology carries its
     *                          own redistribution terms this is derived from
     *                          them rather than passed in.
     */
    public static Report check(DataSource ds, String domain, Set<String> resolvedElsewhere) {
        Names.requireDomain(domain);
        List<LooseEnd> looseEnds = new ArrayList<>();
        List<LooseEnd> byDesign = new ArrayList<>();
        long checked;
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT count(*) FROM state.%s_reference r
                    JOIN state.%s_data o ON o.id = r.owner_id AND NOT o.deleted
                    """.formatted(domain, domain));
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                checked = rs.getLong(1);
            }
            // The join is on text: target_id holds canonical urls as well as
            // ids, and casting one of those to uuid would fail the query rather
            // than report the row.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT o.type, r.owner_id::text, r.ref_type, r.target_type, r.target_id
                    FROM state.%s_reference r
                    JOIN state.%s_data o ON o.id = r.owner_id AND NOT o.deleted
                    LEFT JOIN state.%s_data t
                           ON t.id::text = r.target_id AND NOT t.deleted
                    WHERE t.id IS NULL
                    ORDER BY o.type, r.owner_id, r.ref_type
                    """.formatted(domain, domain, domain));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LooseEnd end = new LooseEnd(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5));
                    if (resolvedElsewhere.contains(end.targetType())) {
                        byDesign.add(end);
                    } else {
                        looseEnds.add(end);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reference closure check failed", e);
        }
        return new Report(checked, looseEnds, byDesign);
    }

    /**
     * Refuses unless every reference resolves — the shape a <b>backup
     * restore</b> needs.
     *
     * <p>A restored installation whose records point at nothing is broken, and
     * a warning during a disaster recovery is a message nobody reads. A
     * portable or foreign import is the opposite case and should call
     * {@link #check} instead, name what is missing, and let the operator
     * decide.
     */
    public static Report requireWhole(DataSource ds, String domain,
            Set<String> resolvedElsewhere) {
        Report report = check(ds, domain, resolvedElsewhere);
        if (!report.isWhole()) {
            throw new IncompleteRestoreException(report);
        }
        return report;
    }

    /** A restore that would leave records pointing at nothing. */
    public static class IncompleteRestoreException extends RuntimeException {
        private final transient Report report;

        public IncompleteRestoreException(Report report) {
            super("restore refused: " + report.describe());
            this.report = report;
        }

        public Report report() {
            return report;
        }
    }
}
