package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.definitions.DefinitionStore;
import cloud.jengu.dbo.definitions.FaceFunctions;
import cloud.jengu.dbo.definitions.FaceImage;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Bringing a tenant up from a face somebody already cut.
 *
 * <p>The subscriber's own work is unchanged in meaning: it ends up holding
 * what its face publishes, at a known position on its face's feed, with every
 * row marked as having come from there. It just does not read them one at a
 * time to get there.
 *
 * <p><b>Three things have to be true afterwards, or the tenant is not the same
 * as one that read the chain.</b> The rows have to be there — that is the
 * image. Each has to say it came from the face rather than from this tenant,
 * because provenance is what a read reports and what a later change from
 * upstream is matched against; since nothing arrived through the stream, those
 * rows are written here. And the stream has to be standing where the image was
 * cut, so that everything published after the cut is still read and nothing
 * before it is read twice.
 *
 * <p><b>Against the upstream's feed, not this tenant's.</b> The position in
 * the manifest was minted by the root when the image was taken, so it means
 * something only on the root's feed — which is the same feed the subscriber's
 * stream reads. Standing the tenant's own consumer there instead would be
 * putting a position from one feed onto another, and the two have no reason
 * to agree.
 *
 * <p><b>Refusing costs nothing.</b> An image that is missing, unreadable or
 * from another release leaves the tenant to come up the way tenants came up
 * before there were images: slower, and correct. So every refusal here is a
 * line in the log and a return, never a failure.
 */
final class FaceBringUp {

    private static final Logger LOG = LoggerFactory.getLogger(FaceBringUp.class);

    private FaceBringUp() {}

    /**
     * Whether the face came from an image, what happened either way, and
     * whether cutting a fresh one would change the answer.
     *
     * <p>The last is the difference between "there is nothing to load" and
     * "there is something and it is no use": a missing image and one from the
     * release before are both fixed by cutting, and a schema that already
     * holds a face is not.
     */
    record Outcome(boolean fromImage, String said, boolean worthCutting) {}

    /**
     * Loads the face's image into this tenant, if there is one it can use.
     *
     * @param directory where the operator keeps images, or null when it keeps none
     * @param face      the face code, which names the image
     * @param into      the tenant's own database
     * @param upstream  the root this tenant takes its face from
     * @param consumer  the name the stream reads the root's feed under
     */
    static Outcome from(Path directory, String face, DataSource into,
            TenantRuntimeManager.TenantRuntime upstream, String consumer, String dependency,
            java.util.Set<String> declaredTypes) {
        if (directory == null) {
            return new Outcome(false, "no image directory is configured", false);
        }
        Path image = directory.resolve(face + ".faceimage");
        if (!Files.isReadable(image)) {
            return new Outcome(false, "no image of face '" + face + "' is kept yet", true);
        }

        FaceImage.Facts expected = new FaceImage.Facts(FaceRootPackages.carried(face), face,
                FaceFunctions.installedIn(into), DefinitionStore.SHAPE);
        FaceImage.Acceptance answer;
        try (InputStream bytes = Files.newInputStream(image)) {
            answer = FaceImage.accept(into, expected, bytes);
        } catch (IOException unreadable) {
            return new Outcome(false, "the image at " + image + " could not be read: "
                    + unreadable.getMessage(), false);
        }
        if (answer instanceof FaceImage.Acceptance.Refused refused) {
            // An image from another release is worth replacing; one refused
            // because this database already holds a face is not, and cutting
            // over and over because of it would be the loop nobody notices.
            return new Outcome(false, refused.why(), !refused.why().contains("already holds"));
        }

        FaceImage.Acceptance.Accepted accepted = (FaceImage.Acceptance.Accepted) answer;
        try {
            long marked = markAsComingFromTheFace(into, dependency, declaredTypes);
            String stood = standAtTheCut(upstream.definitionsFeed(), consumer,
                    accepted.manifest().cursor());
            return new Outcome(true, "brought its face up from the image at " + image
                    + ": rows=" + accepted.rows() + " marked=" + marked
                    + " cutAt=" + accepted.manifest().cutAt() + stood, false);
        } catch (SQLException e) {
            // The rows are in and their provenance is not. Saying so is the
            // only honest move: a tenant serving definitions that claim to be
            // its own would report the wrong source on every read of them.
            throw new IllegalStateException("the face was loaded from its image and could not "
                    + "be marked as having come from '" + dependency + "'", e);
        }
    }

    /**
     * Writes what the stream would have written as each row arrived.
     *
     * <p>The version recorded is the row's own, because a definition copied
     * from the face carries the version it had there — which is what a later
     * change from upstream is compared against to decide whether it is news.
     *
     * <p><b>Only the types this tenant declared.</b> An image is the whole
     * face, cut once and handed to every tenant on it, and a tenant asks for
     * the part of it that it wants. Marking the rest as having come from the
     * face would claim a provenance the stream would never have written, and
     * would have a later change to a type nobody subscribed to matched
     * against a row nobody is watching. What is left unmarked is the tenant's
     * own — which is what it is: the runtime writes a face's own vocabulary
     * into every tenant, and that write is idempotent by canonical identity,
     * so it finds these and adds nothing.
     */
    private static long markAsComingFromTheFace(DataSource into, String dependency,
            java.util.Set<String> declaredTypes) throws SQLException {
        String tables = Domains.tables(Domains.DEFINITIONS);
        try (Connection c = into.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO %s_sync_origin
                         (object_id, dependency, type, source_version_id,
                          applied_payload_version, synced_at)
                     SELECT d.id, ?, d.type, d.version_id, d.payload_version, now()
                       FROM %s_data d
                      WHERE d.type = ANY(?)
                     ON CONFLICT (object_id) DO NOTHING"""
                     .formatted(tables, tables))) {
            ps.setString(1, dependency);
            ps.setArray(2, c.createArrayOf("text", declaredTypes.toArray()));
            return ps.executeUpdate();
        }
    }

    /**
     * Puts the stream where the image was cut, if the face has been there.
     *
     * <p>A null position means the face had published nothing when it was cut,
     * which reads as the beginning — and the beginning is right, since there
     * is nothing behind it to skip.
     *
     * <p>A position the face has NOT reached means the image outlived the
     * database it was cut from: a root rebuilt from its packages starts its
     * feed over, and a position from the old one names somewhere the new one
     * has never been. Nothing in the manifest catches that — the release, the
     * packages and the SQL all still agree — so it is caught here, against the
     * feed itself. The tenant starts at the beginning and reads the face it
     * already holds, which costs a drain and skips nothing; standing where the
     * image said would skip whatever the rebuilt face published, in silence.
     *
     * @return what was done, for the line that says how the tenant came up
     */
    private static String standAtTheCut(ChangeFeed feed, String consumer, String cursor) {
        if (feed instanceof cloud.jengu.dbo.postgres.PgChangeFeed pg && !pg.hasReached(cursor)) {
            feed.resetConsumer(consumer, null);
            return " — but the face has not reached the position the image was cut at, so it "
                    + "was cut from a database this one is not; reading from the beginning";
        }
        feed.resetConsumer(consumer, cursor);
        LOG.debug("stream {} stands where the face was cut", consumer);
        return "";
    }
}
