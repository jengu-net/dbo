package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.stream.DbosStepScheduler;
import cloud.jengu.dbo.stream.SubstrateConnections;
import cloud.jengu.dbo.work.StepScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store's own clock, on two nodes over one tenant's database: each tick
 * of a step is handed over once, whichever node's scheduler noticed it.
 *
 * <p>The one test that waits out a tick, because what it proves is the
 * durable layer's timing and nothing can be turned by hand there; everything
 * the store does with a tick is {@link ATickStartsOneRunOnEveryNodeIT}'s.
 */
@Tag("integration")
class TheStoresClockFiresATickOnceIT {

    @Test
    @DisplayName("two nodes' clocks over one database hand each tick of a minute rule over "
            + "once, keep their schedules apart from the tenant's other durable work, and "
            + "stop one no longer declared")
    @Proving(DboPromises.PROC_A_HOSTS_SCHEDULER_IS_TRUSTED_WITH_TIMING_ALONE)
    void eachTickIsHandedOverOnce() throws Exception {
        PostgreSQLContainer<?> postgres = SharedPostgres.get();
        SubstrateConnections database = new SubstrateConnections(
                SharedPostgres.urlFor("TheStoresClockFiresATickOnceIT"),
                postgres.getUsername(), postgres.getPassword());
        Map<Instant, AtomicInteger> handed = new ConcurrentHashMap<>();
        List<String> nodes = new CopyOnWriteArrayList<>();
        StepScheduler.Tick counting = (step, at) -> {
            assertEquals("ward.rounds.walk", step);
            handed.computeIfAbsent(at, ignored -> new AtomicInteger()).incrementAndGet();
        };
        DbosStepScheduler store = new DbosStepScheduler();
        try (StepScheduler.Clock north = store.open("ward", database, (step, at) -> {
                    nodes.add("north");
                    counting.due(step, at);
                });
                StepScheduler.Clock south = store.open("ward", database, (step, at) -> {
                    nodes.add("south");
                    counting.due(step, at);
                })) {
            north.apply(Map.of("ward.rounds.walk", "0 * * * * *"));
            south.apply(Map.of("ward.rounds.walk", "0 * * * * *"));

            long giveUp = System.nanoTime() + java.time.Duration.ofSeconds(150).toNanos();
            while (handed.size() < 2 && System.nanoTime() < giveUp) {
                Thread.sleep(250);
            }
            // Long enough after the second tick for a duplicate to have arrived.
            Thread.sleep(3_000);
            assertTrue(handed.size() >= 2, "no two ticks were handed over in 150 seconds: "
                    + handed);
            handed.forEach((at, times) -> assertEquals(1, times.get(),
                    "the tick at " + at + " was handed over " + times.get() + " times, by "
                            + nodes));

            try (Connection c = database.getConnection(); Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery("SELECT schedule_name FROM "
                            + "dbos_starts.workflow_schedules")) {
                assertTrue(rows.next(), "the schedule is not in the clock's own schema");
                assertEquals("ward.rounds.walk", rows.getString(1));
            }

            north.apply(Map.of());
            try (Connection c = database.getConnection(); Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery("SELECT schedule_name FROM "
                            + "dbos_starts.workflow_schedules")) {
                assertFalse(rows.next(), "a step no longer declared kept its schedule");
            }
        }
    }
}
