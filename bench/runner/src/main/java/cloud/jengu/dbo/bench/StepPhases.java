package cloud.jengu.dbo.bench;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.telemetry.Labels;
import cloud.jengu.dbo.telemetry.Telemetry;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where a step's time goes, on this board.
 *
 * <p>Writes and lookups measure the store; this measures the machinery the
 * store exists to carry. Every activity in a deployment is a step run, and a
 * run is not one duration: it retrieves what it works on, does the work, and
 * writes what came of it. Which of the three dominates decides what to do
 * about a slow deployment, and a single number cannot say.
 *
 * <p><b>The input is a real record, not a marker.</b> Retrieval on a person's
 * record is also decryption — identifying elements are sealed in the payload
 * — so a run whose slot is filled with a Patient measures the cost a
 * deployment actually pays, and one filled with nothing measures a map
 * lookup.
 *
 * <p><b>The step does as little as a step can.</b> What is under measurement
 * is the engine's part: claim, resolve, close. A service that did real work
 * would report its own duration back as the engine's, and the phase that
 * matters would be hidden inside the one that does not.
 */
final class StepPhases {

    /** Process and step, in the shape a declaration and a service both name. */
    private static final String STEP = "bench.work.touch";
    private static final String PATIENT = "http://hl7.org/fhir/StructureDefinition/Patient";

    private final Latency retrieve = new Latency();
    private final Latency execute = new Latency();
    private final Latency write = new Latency();
    private final Latency whole = new Latency();
    private boolean complete = true;

    /** Fewer runs closed than were opened, so the percentiles are over a part. */
    void incomplete() {
        complete = false;
    }

    boolean complete() {
        return complete;
    }

    /**
     * One tenant's runs, from opening them to the last one closed.
     *
     * @param references what each run works on, as the host's own references
     * @return how many runs closed inside the patience
     */
    int drive(String tenant, DataSource dataSource, ObjectStore objects,
            List<String> references, Duration patience, Telemetry reporting) {
        Runs runs = new Runs(objects);
        Declarations declarations = new Declarations(objects,
                new PgChangeFeed(dataSource, WorkModel.DOMAIN), Duration.ofSeconds(30));
        // Declared over the WORK domain, which is the domain a run is
        // written in and the one the lane's feed carries: a step declared
        // over the face's domain produces runs the lane never sees, and a
        // runner that polls forever looks exactly like a slow one.
        StepDeclaration step = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                .taking("subject", PATIENT);

        for (int i = 0; i < references.size(); i++) {
            runs.of(step, RunKind.PIPELINE, tenant + "/" + i,
                    Map.of("subject", references.get(i)));
        }

        Map<String, List<Duration>> observed = new ConcurrentHashMap<>();
        Telemetry both = new Telemetry() {

            @Override
            public void counted(String name, long delta, Labels labels) {
                reporting.counted(name, delta, labels);
            }

            @Override
            public void observed(String name, Duration took, Labels labels) {
                // Kept here AND sent onward: the file is what the next run is
                // compared against, and the collector is where somebody
                // watches it happen. Neither is the other's substitute.
                observed.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>()).add(took);
                reporting.observed(name, took, labels);
            }

            @Override
            public void level(String name, long value, Labels labels) {
                reporting.level(name, value, labels);
            }
        };

        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(20),
                both)) {
            runner.register(new StepService() {

                @Override
                public String step() {
                    return STEP;
                }

                @Override
                public Outcome perform(Work work) {
                    // The payload is TOUCHED, because a retrieval whose
                    // result nobody reads can be optimised into not having
                    // happened — and on a record whose identifying elements
                    // are sealed, reading is the decryption this is here to
                    // measure.
                    long seen = 0;
                    for (StoredObject held : work.inputs().values()) {
                        seen += held.payload() == null ? 0 : held.payload().length;
                    }
                    return Outcome.done(Map.of("touched", seen > 0 ? 1L : 0L));
                }
            });
            runner.attach(Lane.inProcess(tenant, runs,
                    new PgChangeFeed(dataSource, WorkModel.DOMAIN), declarations,
                    "bench-" + tenant,
                    new Executor("bench-" + tenant, "1.0", "cloud.jengu.bench", Scope.BASELINE),
                    objects));
            // STARTED, because attaching a lane is telling the runner where
            // work is and not asking it to go and do any. A runner that was
            // registered, attached and never started polls nothing and says
            // nothing about it — which reads exactly like work that is not
            // arriving.
            runner.start();

            Instant until = Instant.now().plus(patience);
            while (Instant.now().isBefore(until)
                    && counted(observed, "dbo.run.duration") < references.size()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // A run that did not close said why, on itself. Reading it back is
        // the difference between "0 of 5 closed" — which sends somebody to
        // the runner's source — and the sentence the step or the lane
        // actually wrote, which is silent everywhere else: a released run is
        // a record, not a log line.
        if (counted(observed, "dbo.run.duration") < references.size()) {
            for (int i = 0; i < references.size(); i++) {
                Run left = runs.byKey(STEP + "/" + tenant + "/" + i).orElse(null);
                if (left != null && left.item() != null) {
                    System.out.println("    " + left.key() + ": " + left.item().message());
                    break;
                }
            }
        }

        record(observed.get("dbo.run.retrieve"), retrieve);
        record(observed.get("dbo.run.execute"), execute);
        record(observed.get("dbo.run.write"), write);
        record(observed.get("dbo.run.duration"), whole);
        return counted(observed, "dbo.run.duration");
    }

    private static int counted(Map<String, List<Duration>> observed, String name) {
        List<Duration> held = observed.get(name);
        return held == null ? 0 : held.size();
    }

    private static void record(List<Duration> durations, Latency into) {
        if (durations == null) {
            return;
        }
        for (Duration took : durations) {
            into.record(took.toNanos());
        }
    }

    /** The four, as the result document carries them. */
    String json() {
        return Json.object(
                Json.field("retrieve", retrieve.json()),
                Json.field("execute", execute.json()),
                Json.field("write", write.json()),
                Json.field("whole", whole.json()));
    }

    String summary() {
        return line("retrieve", retrieve) + line("execute", execute)
                + line("write", write) + line("whole run", whole);
    }

    private static String line(String name, Latency of) {
        return String.format("    %-12s %5d runs  p50 %.2fms  p99 %.2fms%n",
                name, of.count(), of.millis(0.5), of.millis(0.99));
    }
}
