package cloud.jengu.dbo.sample;

/**
 * Changing a record somebody else might also be changing.
 *
 * <p>The ordinary shape of it: read what is there, decide something, write
 * back. What makes it interesting is the gap in the middle — a person reading
 * a screen takes minutes over it, and a batch takes as long as the batch takes
 * — because somebody else can move the record on while you are thinking.
 */
public final class Amending {

    private final Surface hospital;

    public Amending(Surface hospital) {
        this.hospital = hospital;
    }

    /** What the record said at that version, rather than what it says now. */
    public Answer asItWas(String type, String id, int version) {
        return hospital.readVersion(type, id, version);
    }

    /**
     * Write a change back, and let it be refused if the ground moved.
     *
     * <p>The validator comes from the read this change was decided on, so the
     * store can tell whether that is still the current version. If it is not,
     * this is refused and the caller finds out while it still has both the
     * change and the reason for it — instead of overwriting somebody, being
     * told it succeeded, and having the missing change found weeks later by
     * whoever needed it.
     */
    public Answer amend(String type, String id, Answer whatYouRead, String json) {
        return hospital.changeIfStillAt(type, id, whatYouRead.etag(), json);
    }
}
