package cloud.jengu.dbo.core.api.feed;

/** What a change event did. */
public enum ChangeKind {
    CREATED,
    UPDATED,
    DELETED;

    public static ChangeKind fromCode(String code) {
        return switch (code) {
            case "C" -> CREATED;
            case "U" -> UPDATED;
            case "D" -> DELETED;
            default -> throw new IllegalArgumentException("unknown change kind: " + code);
        };
    }
}
