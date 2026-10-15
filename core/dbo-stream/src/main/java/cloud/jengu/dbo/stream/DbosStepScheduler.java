package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.work.StepScheduler;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowSchedule;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The store's own scheduler: DBOS, in the tenant's own database.
 *
 * <p>Schedules are rows there, so every node serving the tenant fires the
 * same ones, and a tick's workflow is named by the schedule and its instant,
 * so the nodes that notice the same tick start one workflow of it.
 *
 * <p><b>A schema of its own</b>, because every DBOS instance polls every
 * schedule in its schema and logs an error for each one whose workflow it does
 * not hold — and the tenant's {@code dbos} schema is shared by its
 * subscription delivery and its lane doors, which hold none of these.
 *
 * <p>No backfill: the store reckons missed ticks from the ticks it holds, so DBOS fires only
 * those that come while it runs.
 */
public final class DbosStepScheduler implements StepScheduler {

    static final String SCHEMA = "dbos_starts";

    @Override
    public Clock open(String tenant, DataSource database, Tick tick) {
        return new DbosClock(tenant, database, tick);
    }

    /** The workflow a tick of any step of one tenant runs. */
    public interface Ticks {
        void tick(Instant at, Object step);
    }

    /** What it does: hands the tick to the tenant. */
    public static final class Ticking implements Ticks {

        private final Tick tick;

        Ticking(Tick tick) {
            this.tick = tick;
        }

        @Override
        @Workflow(name = "tick")
        public void tick(Instant at, Object step) {
            tick.due(String.valueOf(step), at);
        }
    }

    private static final class DbosClock implements Clock {

        private final DBOS dbos;

        DbosClock(String tenant, DataSource database, Tick tick) {
            // Its own executor id: on launch an instance recovers the pending
            // workflows of its executor, and two nodes sharing one would each
            // take the other's tick.
            this.dbos = new DBOS(DBOSConfig.defaults("dbo-starts-" + tenant)
                    .withDataSource(database)
                    .withDatabaseSchema(SCHEMA)
                    .withExecutorId("starts-" + tenant + "-" + UUID.randomUUID())
                    .withMigrate(true));
            dbos.registerProxy(Ticks.class, new Ticking(tick));
            dbos.launch();
        }

        @Override
        public void apply(Map<String, String> rules) {
            Map<String, String> held = new java.util.HashMap<>();
            for (WorkflowSchedule schedule : dbos.listSchedules(null, null, null)) {
                held.put(schedule.scheduleName(), schedule.cron());
            }
            // Only what changed: applying replaces a schedule whole, and every
            // node comes up applying the same declaration.
            List<WorkflowSchedule> changed = rules.entrySet().stream()
                    .filter(rule -> !rule.getValue().equals(held.get(rule.getKey())))
                    .map(rule -> new WorkflowSchedule(rule.getKey(), "tick",
                            Ticking.class.getName(), rule.getValue())
                            .withContext(rule.getKey())
                            // the zone the store reckons missed ticks in
                            .withCronTimezone(java.time.ZoneOffset.UTC))
                    .toList();
            if (!changed.isEmpty()) {
                dbos.applySchedules(changed);
            }
            // Applying leaves what it does not name, so a step no longer
            // declared is taken off by hand.
            for (String name : held.keySet()) {
                if (!rules.containsKey(name)) {
                    dbos.deleteSchedule(name);
                }
            }
        }

        @Override
        public void close() {
            dbos.shutdown();
        }
    }
}
