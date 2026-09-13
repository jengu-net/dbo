package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.ObjectStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * What a deployment did, sent where it reports to.
 *
 * <p>The runs are the record and the spans are a copy of it, so this is a
 * convenience and never the truth: a collector that was down missed some
 * copies and the originals are still in the store, where the same spans can
 * be rendered again.
 *
 * <p><b>Pushed as well as servable, because a surface is not always there.</b>
 * The endpoint that serves these lives on the maintenance path, which a
 * deployment running without an authority does not mount at all — backups
 * are system-plane and a tenant that cannot say who is asking has no
 * business answering. That is right, and it leaves a deployment that reports
 * outward as the only way those deployments are visible.
 *
 * <p><b>By when a run opened, and only once.</b> The window walks forward
 * from the last one sent, so a long bring-up is reported while it is still
 * going and again when it finishes, which is what somebody watching a
 * restore wants; what is not wanted is the same finished run every interval
 * forever.
 */
public final class SpansReported implements AutoCloseable {

    private final Supplier<java.util.Optional<ObjectStore>> history;
    private final String tenant;
    private final BiPredicate<String, String> send;
    private final Duration every;
    private volatile Thread reporter;
    private volatile Instant from = Instant.now();
    private volatile boolean closed;

    /**
     * @param history where the deployment's runs are, resolved each time
     *                because the managing tenant may come up after this does
     * @param send    the document and the signal, answering whether it landed
     */
    public SpansReported(Supplier<java.util.Optional<ObjectStore>> history, String tenant,
            BiPredicate<String, String> send, Duration every) {
        this.history = history;
        this.tenant = tenant;
        this.send = send;
        this.every = every == null || every.isZero() ? Duration.ofSeconds(30) : every;
    }

    /**
     * Reports from this instant rather than from now.
     *
     * <p>The default is now, so a deployment that has been up for a week does
     * not open by posting the week; winding it back is how a deployment that
     * has just been pointed somewhere new says what it has been doing.
     */
    public SpansReported reportingSince(Instant beginning) {
        this.from = beginning;
        return this;
    }

    /** Starts reporting. Nothing happens until something has somewhere to go. */
    public SpansReported start() {
        reporter = Thread.ofVirtual().name("dbo-spans").start(() -> {
            while (!closed) {
                try {
                    Thread.sleep(every);
                } catch (InterruptedException interrupted) {
                    return;
                }
                report();
            }
        });
        return this;
    }

    /** One window, rendered and sent. Visible for a proof that does not wait. */
    public boolean report() {
        ObjectStore store = history.get().orElse(null);
        if (store == null) {
            return false;
        }
        try {
            RunSpans spans = new RunSpans(store);
            Instant since = from;
            Instant until = Instant.now();
            List<RunSpans.Span> rendered = spans.since(since, tenant);
            if (rendered.isEmpty()) {
                from = until;
                return false;
            }
            boolean landed = send.test(spans.asOtlp(rendered, "dbo"), "traces");
            if (landed) {
                // Only on success, so a collector that was down is caught up
                // with rather than skipped past.
                from = until;
            }
            return landed;
        } catch (RuntimeException unreportable) {
            // Reporting is not serving. A deployment whose spans cannot be
            // rendered keeps working and stops reporting, which is the rule
            // the exporter itself keeps.
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread held = reporter;
        if (held != null) {
            held.interrupt();
        }
    }
}
