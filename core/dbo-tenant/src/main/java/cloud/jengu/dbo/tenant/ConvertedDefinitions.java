package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Domains;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a definition that crossed a version is still a definition.
 *
 * <p>Converting downward loses things by nature. A structure constraining an
 * element the older version does not have cannot be said in that version, and
 * a structure built on a resource the older version never had cannot be built
 * at all — and the convertor may produce something for both cases without
 * complaint. What comes out is well-formed, loads, and is not the definition
 * anybody meant.
 *
 * <p><b>So the check is not "did it convert" but "does it still stand on
 * anything".</b> A profile is expanded against the base it is built on; a base
 * the target face has never heard of makes the profile unexpandable, which
 * means nothing can be validated against it. That is checkable exactly here,
 * at the projection, because this is the one tenant that holds both the
 * converted definitions and the face they were converted into.
 *
 * <p><b>Named, not counted.</b> A number would say a zone is partly unservable
 * and leave somebody to find out which part. What a person can act on is the
 * canonical url of the definition and the base it lost.
 */
public final class ConvertedDefinitions {

    private ConvertedDefinitions() {}

    /** One definition that did not survive, and what it was standing on. */
    public record Unfounded(String url, String base) {
        @Override
        public String toString() {
            return url + " is built on " + base;
        }
    }

    /**
     * The definitions this tenant holds whose base it does not.
     *
     * <p>Read from the rows rather than from what just arrived, so the answer
     * is about the state a tenant is actually in: a profile whose base arrives
     * later stops being unfounded without anybody re-examining it, and one
     * whose base never arrives keeps saying so every time it is asked.
     *
     * <p>Logical models are exempt for the reason the face's own check exempts
     * them: a logical model is not a shape any resource is validated against,
     * so a base it cannot reach costs the face nothing it validates with.
     */
    public static List<Unfounded> unfounded(DataSource on) {
        String tables = Domains.tables(Domains.DEFINITIONS);
        List<Unfounded> orphaned = new ArrayList<>();
        try (Connection c = on.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT mine.url, mine.base FROM (
                       SELECT d.envelope #>> '{url,0,v}' AS url,
                              convert_from(d.payload, 'UTF8') AS document
                         FROM %s_data d
                        WHERE d.type = 'StructureDefinition' AND NOT d.deleted) raw,
                       LATERAL (SELECT raw.url AS url,
                                       substring(raw.document
                                           from '"baseDefinition"\\s*:\\s*"([^"]+)"') AS base,
                                       substring(raw.document
                                           from '"kind"\\s*:\\s*"(\\w+)"') AS kind) mine
                      WHERE mine.base IS NOT NULL
                        AND mine.kind IS DISTINCT FROM 'logical'
                        AND NOT EXISTS (
                              SELECT 1 FROM %s_data held
                               WHERE held.type = 'StructureDefinition'
                                 AND NOT held.deleted
                                 AND held.envelope #>> '{url,0,v}' = mine.base)
                      ORDER BY mine.url"""
                     .formatted(tables, tables));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                orphaned.add(new Unfounded(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("what this tenant's definitions are built on "
                    + "could not be read", e);
        }
        return orphaned;
    }

    /** The same, said the way a person reads it. */
    public static String said(List<Unfounded> orphaned) {
        List<String> named = orphaned.stream().limit(5).map(Unfounded::toString).toList();
        return orphaned.size() + " definition(s) are built on bases this face does not carry, "
                + "so nothing can be validated against them: " + String.join("; ", named)
                + (orphaned.size() > named.size() ? "; and " + (orphaned.size() - named.size())
                        + " more" : "");
    }
}
