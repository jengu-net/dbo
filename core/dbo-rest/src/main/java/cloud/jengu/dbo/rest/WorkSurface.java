package cloud.jengu.dbo.rest;

import java.util.Optional;

/**
 * The run's face on the tenant's surface (§15): a run is authored by posting
 * the document the face renders runs as, and read back as the same document
 * as it advances. Authoring is not participation — a participant holds a
 * lane and takes work; whoever authors work holds the tenant's store
 * credential and states an obligation — so this sits on the store surface
 * under the tenant's ordinary write authority, never on the lane. Update and
 * delete never exist here: a run advances through its lane.
 */
public interface WorkSurface {

    /** The run rendered, or empty for an id that is no run. */
    Optional<String> read(String id);

    /**
     * A posted document that names a declared step becomes a run, and the
     * run is what is stored: the document is its rendering, not a second
     * record beside it.
     *
     * @throws Refused where the render's reverse cannot be satisfied — an
     *                 unknown step, an undeclared slot, an unfilled declared
     *                 slot, a key already used — by name
     */
    Authored create(String document);

    /** The run authored, rendered, with where it now lives. */
    record Authored(String id, long versionId, String rendered) {}

    /** What a posting was refused for, with the status HTTP says it in. */
    final class Refused extends RuntimeException {
        private final int status;

        public Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
