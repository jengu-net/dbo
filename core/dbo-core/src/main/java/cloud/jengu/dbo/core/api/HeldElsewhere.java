package cloud.jengu.dbo.core.api;

import java.util.Objects;

/**
 * One version of a record that somewhere else holds too, and who says so.
 *
 * <p>Kept beside the record and never on it. Marking a record would write a
 * version of it, and that version is history that itself has to travel and be
 * kept: on a place's small disk, the mark that should let it go would grow it.
 *
 * @param by who says it is held elsewhere: the lane that was told so, or the
 *           application that knows it
 */
public record HeldElsewhere(String typeName, String id, long version, String by) {

    public HeldElsewhere {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(by, "by");
        if (version < 1) {
            throw new IllegalArgumentException("a version is 1 or later: " + version);
        }
    }
}
