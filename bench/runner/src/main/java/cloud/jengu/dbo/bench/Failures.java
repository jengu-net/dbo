package cloud.jengu.dbo.bench;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Requests that did not succeed, kept by status.
 *
 * <p>A benchmark that ignores its failures reports the latency of the requests
 * that happened to work, which is not a measurement of the system — and it
 * fails in the flattering direction, because the requests that break are
 * usually the slow ones. A run with any failure in it is marked invalid; the
 * numbers stay in the file as evidence, but nothing may quote them.
 *
 * <p>Status {@code -1} is an exception rather than a response: no status came
 * back at all.
 */
final class Failures {

    private final Map<Integer, LongAdder> byStatus = new ConcurrentHashMap<>();

    void record(int status) {
        byStatus.computeIfAbsent(status, s -> new LongAdder()).increment();
    }

    boolean none() {
        return byStatus.isEmpty();
    }

    long total() {
        return byStatus.values().stream().mapToLong(LongAdder::sum).sum();
    }

    String byStatus() {
        return byStatus.entrySet().stream()
                .map(e -> (e.getKey() == -1 ? "exception" : String.valueOf(e.getKey()))
                        + "×" + e.getValue().sum())
                .collect(Collectors.joining(", ", "(", ")"));
    }

    String json() {
        return Json.object(
                Json.field("total", total()),
                Json.raw("byStatus", byStatus.entrySet().stream()
                        .map(e -> Json.quote(e.getKey() == -1 ? "exception"
                                : String.valueOf(e.getKey())) + ":" + e.getValue().sum())
                        .collect(Collectors.joining(",", "{", "}"))));
    }
}
