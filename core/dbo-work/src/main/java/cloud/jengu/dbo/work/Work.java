package cloud.jengu.dbo.work;

/**
 * What a candidate is being offered, in engine words.
 *
 * <p>Enough to decide whether to claim it and nothing more: a precondition that
 * needed the payload would be reading the subject of the work in order to
 * decide who runs it, and resolution happens in front of everyone who can see
 * that work exists.
 */
public record Work(String process, String step, String reference, String correlation) {

    public static Work of(String process, String step, String reference) {
        return new Work(process, step, reference, null);
    }
}
