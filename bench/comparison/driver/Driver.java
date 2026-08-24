import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The comparative ingest driver.
 *
 * <p>Runs OFF the Pi on purpose. The existing bench keeps its driver on the
 * appliance so the LAN stays out of the measurement, which is right for that
 * benchmark and wrong for this one: at fifty concurrent writers the driver is
 * itself a substantial load, and a four-core Pi cannot host both the thing
 * under test and the thing testing it without the measurement becoming a
 * measurement of the contest between them. The price is the network, and it
 * is paid by measuring the floor separately.
 *
 * <p>Two modes, moving the SAME resources:
 * <ul>
 *   <li><b>batch</b> — the whole bundle as one transaction, which is how an
 *       integration hands over a patient's record.</li>
 *   <li><b>record</b> — every entry as its own request, one bundle to one
 *       worker, which is how a stream of events arrives.</li>
 * </ul>
 */
public final class Driver {

    public static void main(String[] args) throws Exception {
        String base = arg(args, "--base", null);
        String mode = arg(args, "--mode", "batch");
        int threads = Integer.parseInt(arg(args, "--threads", "1"));
        Path cohort = Path.of(arg(args, "--cohort", "cohort"));
        Path out = Path.of(arg(args, "--out", "result.json"));
        String label = arg(args, "--label", "unlabelled");

        List<Path> files = new ArrayList<>();
        try (var s = Files.list(cohort)) {
            s.filter(f -> f.toString().endsWith(".json")).sorted().forEach(files::add);
        }

        // Read every bundle into memory BEFORE the clock starts. Reading from
        // the driver's disk inside the measured window would put the driver's
        // storage in a number about the server's.
        List<byte[]> bundles = new ArrayList<>(files.size());
        List<List<byte[]>> entriesPer = new ArrayList<>(files.size());
        long resources = 0;
        for (Path f : files) {
            byte[] b = Files.readAllBytes(f);
            bundles.add(b);
            List<byte[]> entries = splitEntries(b);
            entriesPer.add(entries);
            resources += entries.size();
        }
        System.out.println("cohort: " + bundles.size() + " bundles, " + resources + " resources");

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < bundles.size(); i++) {
            queue.add(i);
        }

        // Latencies are kept whole rather than summarised as they arrive: p99
        // computed from a running estimate is an estimate of a tail, and the
        // tail is the part of a write path worth arguing about.
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        Map<Integer, AtomicLong> statuses = new ConcurrentHashMap<>();
        AtomicLong sent = new AtomicLong();
        AtomicLong failed = new AtomicLong();

        CountDownLatch done = new CountDownLatch(threads);
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try {
                    Integer i;
                    while ((i = queue.poll()) != null) {
                        if (mode.equals("batch")) {
                            one(http, base, bundles.get(i), latencies, statuses, sent, failed);
                        } else {
                            // One bundle to one worker, its entries in order:
                            // the shape a stream of events from a single
                            // source actually has.
                            for (byte[] entry : entriesPer.get(i)) {
                                String type = resourceType(entry);
                                if (type == null) {
                                    continue;
                                }
                                one(http, base + "/" + type, entry,
                                        latencies, statuses, sent, failed);
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        long elapsedNanos = System.nanoTime() - start;

        List<Long> sortedLatencies = new ArrayList<>(latencies);
        sortedLatencies.sort(Comparator.naturalOrder());
        double seconds = elapsedNanos / 1e9;

        StringBuilder json = new StringBuilder(1024);
        json.append("{\"label\":\"").append(label).append('"')
                .append(",\"base\":\"").append(base).append('"')
                .append(",\"mode\":\"").append(mode).append('"')
                .append(",\"threads\":").append(threads)
                .append(",\"bundles\":").append(bundles.size())
                .append(",\"resources\":").append(resources)
                .append(",\"requests\":").append(sent.get())
                .append(",\"failed\":").append(failed.get())
                .append(",\"seconds\":").append(round(seconds))
                .append(",\"resourcesPerSecond\":").append(round(resources / seconds))
                .append(",\"requestsPerSecond\":").append(round(sent.get() / seconds))
                .append(",\"latencyMs\":{")
                .append("\"p50\":").append(round(pct(sortedLatencies, 50) / 1e6))
                .append(",\"p95\":").append(round(pct(sortedLatencies, 95) / 1e6))
                .append(",\"p99\":").append(round(pct(sortedLatencies, 99) / 1e6))
                .append(",\"max\":").append(round(pct(sortedLatencies, 100) / 1e6))
                .append("},\"statuses\":{");
        boolean first = true;
        for (var e : new java.util.TreeMap<>(statuses).entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(e.getKey()).append("\":").append(e.getValue().get());
            first = false;
        }
        json.append("}}");
        Files.writeString(out, json.toString());
        System.out.println(json);
    }

    private static void one(HttpClient http, String url, byte[] body,
            ConcurrentLinkedQueue<Long> latencies, Map<Integer, AtomicLong> statuses,
            AtomicLong sent, AtomicLong failed) {
        long t0 = System.nanoTime();
        int status;
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/fhir+json")
                            .timeout(Duration.ofMinutes(10))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            status = r.statusCode();
        } catch (IOException | InterruptedException e) {
            status = 0;
        }
        latencies.add(System.nanoTime() - t0);
        statuses.computeIfAbsent(status, k -> new AtomicLong()).incrementAndGet();
        sent.incrementAndGet();
        if (status < 200 || status >= 300) {
            failed.incrementAndGet();
        }
    }

    /**
     * The entries of a transaction bundle, each as its own document.
     *
     * <p>Scanned rather than parsed into an object model. A driver that
     * deserialises 50,000 resources to send them spends its own CPU on the
     * shape of the data, and at fifty concurrent writers that CPU is the
     * thing most likely to become the limit — measuring the driver instead of
     * the server.
     */
    private static List<byte[]> splitEntries(byte[] bundle) {
        String s = new String(bundle, StandardCharsets.UTF_8);
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while ((i = s.indexOf("\"resource\":", i)) >= 0) {
            int open = s.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            int j = open;
            for (; j < s.length(); j++) {
                char c = s.charAt(j);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '{') {
                        depth++;
                    } else if (c == '}' && --depth == 0) {
                        break;
                    }
                }
            }
            out.add(s.substring(open, j + 1).getBytes(StandardCharsets.UTF_8));
            i = j + 1;
        }
        return out;
    }

    private static String resourceType(byte[] resource) {
        String s = new String(resource, 0, Math.min(resource.length, 200),
                StandardCharsets.UTF_8);
        int i = s.indexOf("\"resourceType\":\"");
        if (i < 0) {
            return null;
        }
        int start = i + 16;
        int end = s.indexOf('"', start);
        return end < 0 ? null : s.substring(start, end);
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(i, sorted.size() - 1)));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
