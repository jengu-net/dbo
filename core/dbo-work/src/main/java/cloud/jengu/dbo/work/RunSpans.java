package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What happened, as spans.
 *
 * <p>Not a second record of anything. A run already has everything a span
 * needs and was keeping it in its own vocabulary: a key, a parent, a
 * correlation that identifies the work rather than the step, a trace the
 * store echoes and never interprets, and a history whose first version is
 * when it opened and whose last is when it stopped. This renames those into
 * the words a tracing tool reads.
 *
 * <p><b>The record is the original and this is the copy</b> — the opposite
 * way round from how tracing usually works, where a span is emitted as the
 * work happens and the durable record, if any, comes later. Here the run
 * survives a restart, an export and a restore, and a collector that was down
 * loses nothing: the spans can be rendered again from rows that are still
 * there.
 *
 * <p><b>Nothing from a payload.</b> A span carries the run's own words about
 * itself — which tenant, which process, which step, what it tallied, how it
 * ended — and never the step's words about what it was working on. That is
 * the same rule the run's labels already keep, and it is why this can be
 * sent somewhere a clinical record may not go.
 */
public final class RunSpans {

    private final ObjectStore store;

    public RunSpans(ObjectStore store) {
        this.store = store;
    }

    /** One run, rendered: when it opened, when it stopped, and under what. */
    public record Span(String traceId, String spanId, String parentSpanId, String name,
            Instant began, Instant ended, String tenant, String key, Map<String, Long> tally,
            String outcome) {}

    /**
     * Every run that opened in this window, as spans.
     *
     * <p>By when it OPENED rather than when it ended, because a bring-up that
     * is still going is the one somebody is asking about — a window that only
     * caught finished work would answer "nothing is happening" while a
     * restore was an hour in.
     */
    public List<Span> since(Instant from, String tenant) {
        List<Span> spans = new ArrayList<>();
        List<Run> opened = new ArrayList<>();
        Map<String, Instant> began = new java.util.HashMap<>();
        Map<String, Instant> ended = new java.util.HashMap<>();
        Map<String, String> parents = new java.util.HashMap<>();
        for (StoredObject held : store.select(Criteria.of(WorkModel.TYPE))) {
            List<StoredObject> versions = store.history(WorkModel.TYPE, held.id());
            if (versions.isEmpty()) {
                continue;
            }
            Run run = Run.of(held);
            // Every run's parentage, not only the window's, because the root
            // a window's runs hang from may have opened before it — a restore
            // an hour in is exactly the case this is for.
            parents.put(run.key(), run.parent());
            Instant opening = versions.get(0).lastUpdated();
            if (opening.isBefore(from)) {
                continue;
            }
            opened.add(run);
            began.put(run.key(), opening);
            ended.put(run.key(), held.lastUpdated());
        }
        for (Run run : opened) {
            spans.add(new Span(
                    // The trace the work was handed, or the correlation that
                    // identifies it, or the key of the run its parentage ends
                    // at. Its own key only when it is that root itself: a
                    // tree whose branches each invented a trace is not a
                    // tree, it is one drawing per branch.
                    identifier(run.trace(), run.correlation(), rootOf(run.key(), parents)),
                    identifier(run.key(), null, run.key()),
                    run.parent() == null ? null : identifier(run.parent(), null, run.parent()),
                    run.process() + "/" + run.step(),
                    began.get(run.key()),
                    // The last version is when it stopped being written to,
                    // which for a closed run is when it closed and for one
                    // still open is now-ish. A span for unfinished work is
                    // honest about that through its outcome rather than by
                    // pretending it ended.
                    ended.get(run.key()),
                    tenant,
                    run.key(),
                    run.tally(),
                    run.holder().wire()));
        }
        return List.copyOf(spans);
    }

    /**
     * The same, in the shape OTLP reads.
     *
     * <p>Rendered here rather than by whoever serves it, because the mapping
     * from a run to a span is this class's claim and a second renderer would
     * be a second claim. What a caller decides is where it goes.
     */
    public String asOtlp(List<Span> spans, String service) {
        StringBuilder out = new StringBuilder("{\"resourceSpans\":[{\"resource\":{"
                + "\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"")
                .append(escaped(service)).append("\"}}]},\"scopeSpans\":[{\"scope\":{"
                        + "\"name\":\"dbo.work\"},\"spans\":[");
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            if (i > 0) {
                out.append(',');
            }
            // A trace id is sixteen bytes and a span id is eight, so the
            // derived half-width ids are doubled rather than padded: a viewer
            // that reads them as hex must get something the right length or
            // it drops the span without saying so.
            out.append("{\"traceId\":\"").append(span.traceId()).append(span.traceId())
                    .append("\",\"spanId\":\"").append(span.spanId()).append("\"");
            if (span.parentSpanId() != null) {
                out.append(",\"parentSpanId\":\"").append(span.parentSpanId()).append("\"");
            }
            out.append(",\"name\":\"").append(escaped(span.name()))
                    .append("\",\"kind\":1")
                    .append(",\"startTimeUnixNano\":\"")
                    .append(span.began().getEpochSecond() * 1_000_000_000L
                            + span.began().getNano()).append("\"")
                    .append(",\"endTimeUnixNano\":\"")
                    .append(span.ended().getEpochSecond() * 1_000_000_000L
                            + span.ended().getNano()).append("\"")
                    .append(",\"attributes\":[")
                    // WHERE the history lives, and WHAT the run was about.
                    // They are not the same tenant and a span that carried
                    // only the first said every bring-up in the deployment
                    // was the managing tenant's own.
                    .append(attribute("dbo.deployment", span.tenant()))
                    .append(',').append(attribute("dbo.run.key", span.key()))
                    .append(',').append(attribute("dbo.holder", span.outcome()));
            for (Map.Entry<String, Long> counted : span.tally().entrySet()) {
                out.append(",{\"key\":\"dbo.tally.").append(escaped(counted.getKey()))
                        .append("\",\"value\":{\"intValue\":\"").append(counted.getValue())
                        .append("\"}}");
            }
            out.append("]}");
        }
        return out.append("]}]}]}").toString();
    }

    /**
     * The key a run's parentage ends at, following it as far as it is known.
     *
     * <p>A parent that is not in the store any more ends the walk and becomes
     * the root, which keeps its children in one trace rather than scattering
     * them. The depth cap is not for cycles a run can legitimately have —
     * there are none — but because this reads rows and rows can be wrong.
     */
    private static String rootOf(String key, Map<String, String> parents) {
        String at = key;
        for (int depth = 0; depth < 64; depth++) {
            String above = parents.get(at);
            if (above == null || above.equals(at)) {
                return at;
            }
            at = above;
        }
        return at;
    }

    private static String attribute(String key, String value) {
        return "{\"key\":\"" + key + "\",\"value\":{\"stringValue\":\""
                + escaped(value == null ? "" : value) + "\"}}";
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * A stable id from a name.
     *
     * <p>Derived rather than generated, so rendering the same run twice gives
     * the same span twice instead of two spans that a viewer draws side by
     * side. A collector that missed a window and asked again gets the answer
     * it would have got.
     */
    private static String identifier(String first, String second, String fallback) {
        String from = first != null ? first : second != null ? second : fallback;
        long hash = 1125899906842597L;
        for (int i = 0; i < from.length(); i++) {
            hash = 31 * hash + from.charAt(i);
        }
        return String.format("%016x", hash);
    }
}
