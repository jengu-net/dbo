package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class BenchShapeProbeIT {

    @Test
    void aRunWithFilledSlotsIsClaimed() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("BenchShapeProbeIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        PgObjectStore store = new PgObjectStore(ds, WorkModel.registrations());
        Runs runs = new Runs(store);
        Declarations declarations = new Declarations(store,
                new PgChangeFeed(ds, WorkModel.DOMAIN), Duration.ofSeconds(30));
        StepDeclaration step = StepDeclaration.of("bench.work.touch", "1.0", WorkModel.DOMAIN)
                .taking("subject", "http://hl7.org/fhir/StructureDefinition/Patient");
        var byPipeline = runs.pipeline("bench.work", "touch", "bench.work/touch/probe-pipeline",
                java.util.List.of(WorkModel.DOMAIN));
        var bySlots = runs.of(step, RunKind.PIPELINE, "probe/0",
                Map.of("subject", "Patient/none"));
        System.out.println("PIPELINE RUN: " + byPipeline);
        System.out.println("SLOTTED RUN: " + bySlots);

        System.out.println("READ BACK: " + runs.byKey("bench.work.touch/probe/0"));
        var participation = new cloud.jengu.dbo.work.Participation(runs,
                new PgChangeFeed(ds, WorkModel.DOMAIN), "probe-reader",
                java.util.Set.of("touch"),
                new Executor("probe-reader", "1.0", "cloud.jengu.bench", Scope.BASELINE));
        System.out.println("POLLED: " + participation.poll(50).stream()
                .map(cloud.jengu.dbo.work.Run::key).toList());

        AtomicInteger performed = new AtomicInteger();
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(1), Duration.ofMillis(20))) {
            runner.register(new StepService() {
                @Override
                public String step() {
                    return "bench.work.touch";
                }

                @Override
                public Outcome perform(Work work) {
                    System.out.println("PERFORMED: " + work.run().key());
                    performed.incrementAndGet();
                    return Outcome.done(Map.of("touched", 1L));
                }
            });
            runner.attach(Lane.inProcess("probe", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                    declarations, "bench-probe",
                    new Executor("bench-probe", "1.0", "cloud.jengu.bench", Scope.BASELINE),
                    store));
            Eventually.cycling(runner, "the run reached the service", () -> performed.get() > 1);
        }
        assertTrue(performed.get() > 0, "nothing was claimed");
    }
}
