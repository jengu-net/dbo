package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.UuidV7;
import cloud.jengu.dbo.stream.StepClock;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunSlot;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.StepScheduler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One tenant's steps that start themselves.
 *
 * <p><b>Every run they start is a child of the tenant's system run</b>, one
 * endless run standing for the work nobody asked for, so a reader finds why a run happened by
 * its parent and its key: {@code tick/<instant>}, {@code now/<id>}.
 *
 * <p><b>The scheduler is trusted with the timing alone.</b> A tick becomes a
 * run keyed by its instant, so a scheduler telling every node finds one run;
 * whether a step is paused is read here when the tick arrives; and what was
 * missed while no node ran is reckoned here, from the last tick this database
 * holds, when the clock opens.
 *
 * <p><b>One current entry per step</b>, updated in place, in a table beside the tenant's
 * records; as a record, a step every minute would be half a million versions a year.
 */
final class SelfStarting implements AutoCloseable {

    /** The process the tenant's own acts on its work are recorded under. */
    static final String PROCESS = "dbo.tenant.system";

    private static final String SERVE = "serve";

    private final String tenant;
    private final DataSource database;
    private final Runs runs;
    private final StepSurface surface;
    private final boolean place;
    private final StepScheduler scheduler;
    private final Clock clock;
    private final java.util.function.BiFunction<String, String, Optional<byte[]>> read;
    private volatile Map<String, TenantSpec.Step> steps;
    private volatile Run system;
    private volatile StepScheduler.Clock ticking;

    SelfStarting(String tenant, DataSource database, Runs runs, StepSurface surface,
            List<TenantSpec.Step> steps, boolean place, StepScheduler scheduler, Clock clock,
            java.util.function.BiFunction<String, String, Optional<byte[]>> read) {
        this.tenant = tenant;
        this.read = read;
        this.database = database;
        this.runs = runs;
        this.surface = surface;
        this.place = place;
        this.scheduler = scheduler;
        this.clock = clock;
        this.steps = startingHere(steps, place);
    }

    /** The steps of a declaration that start themselves where it is served. */
    static Map<String, TenantSpec.Step> startingHere(List<TenantSpec.Step> steps,
            boolean place) {
        Map<String, TenantSpec.Step> here = new LinkedHashMap<>();
        for (TenantSpec.Step step : steps) {
            TenantSpec.Starts starts = step.starts();
            if (starts != null && switch (starts.where()) {
                case EVERYWHERE -> true;
                case ORIGIN -> !place;
                case PLACES -> place;
            }) {
                here.put(step.code(), step);
            }
        }
        return here;
    }

    /** The tenant's system run, found or written, then what was missed, then the clock. */
    SelfStarting open() {
        ensureTable();
        system = runs.sweep(PROCESS, SERVE, tenant);
        reckonMissed();
        clockInLine();
        // Once as it opens, then on the hourly beat: a tenant whose nodes come
        // and go faster than that would otherwise keep everything.
        letGo();
        return this;
    }

    /** The declaration changed: the clock is brought into line with it in place. */
    synchronized void redeclared(List<TenantSpec.Step> declared) {
        steps = startingHere(declared, place);
        reckonMissed();
        clockInLine();
    }

    /**
     * Opened only for a tenant with a step on a clock, because opening is a
     * scheduler of its own over the tenant's database.
     */
    private synchronized void clockInLine() {
        Map<String, String> rules = rules();
        if (ticking == null && rules.isEmpty()) {
            return;
        }
        if (ticking == null) {
            ticking = scheduler.open(tenant, database, this::tick);
        }
        ticking.apply(rules);
    }

    private Map<String, String> rules() {
        Map<String, String> rules = new LinkedHashMap<>();
        steps.values().stream().filter(step -> step.starts().every() != null)
                .forEach(step -> rules.put(step.code(), step.starts().every()));
        return rules;
    }

    /** The system run, for a reader of its children. */
    Run system() {
        return system;
    }

    /**
     * A tick of a step, on any node, once or more: one run of it, unless the
     * step is paused or no longer starts here.
     */
    void tick(String code, Instant at) {
        TenantSpec.Step step = steps.get(code);
        if (step == null || step.starts().every() == null) {
            return;
        }
        Entry entry = entry(code);
        if (entry.paused()) {
            // Not missed: nobody wanted it. Resuming starts at the next tick.
            advance(code, at, null);
            return;
        }
        Run run = surface.startedOn(step, "tick/" + at, Map.of(), system, null);
        advance(code, at, run.key());
    }

    /**
     * A record that matched a step's search, as its feed reported a change
     * to it: one run of the step over it for that version, or — for a step
     * due at a moment the record names — one run once the moment has come,
     * and until then the moment, for the record to be looked at again then.
     * Nothing for a paused step: what changed while it was paused is not
     * started on resuming.
     */
    Instant matched(String code, String type, String id, long version) {
        TenantSpec.Step step = steps.get(code);
        if (step == null || step.starts().every() != null || entry(code).paused()) {
            return null;
        }
        TenantSpec.Starts starts = step.starts();
        Map<String, RunSlot> inputs = Map.of(slotOf(step), RunSlot.referring(type + "/" + id));
        if (starts.change() != null) {
            started(code, surface.startedOn(step, "change/" + id + "/" + version, inputs,
                    system, null).key());
            return null;
        }
        Instant moment = read.apply(type, id).map(payload -> momentIn(payload, starts.at()))
                .orElse(null);
        if (moment == null) {
            // A record naming no moment is never due, and is looked at again
            // only when it changes.
            return null;
        }
        Instant due = moment.plus(starts.after());
        if (due.isAfter(clock.instant())) {
            return due;
        }
        started(code, surface.startedOn(step, "due/" + id, inputs, system, null).key());
        return null;
    }

    /** The one slot of the type that starts it, which the record goes into. */
    private static String slotOf(TenantSpec.Step step) {
        String type = step.starts().type();
        return step.slots().entrySet().stream()
                .filter(slot -> cloud.jengu.dbo.core.process.SlotShape.of(slot.getValue())
                        .type().equals(type))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    /**
     * The moment an element of a record names: a dotted path from the
     * resource, read as a FHIR instant, dateTime or date — a date being its
     * first moment, in UTC.
     */
    static Instant momentIn(byte[] payload, String path) {
        Object node = Json.parse(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
        for (String part : path.split("\\.")) {
            node = node instanceof Map<?, ?> object ? object.get(part) : null;
        }
        if (!(node instanceof String written)) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(written).toInstant();
        } catch (java.time.format.DateTimeParseException notADateTime) {
            try {
                return java.time.LocalDate.parse(written).atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant();
            } catch (java.time.format.DateTimeParseException notADate) {
                return null;
            }
        }
    }

    /**
     * Each clock step's ticks since the last this database holds, made what
     * the step declared: one run for the latest of them, or none — and either
     * way recorded under the system run, saying how many.
     */
    private void reckonMissed() {
        Instant now = clock.instant();
        for (TenantSpec.Step step : steps.values()) {
            TenantSpec.Starts starts = step.starts();
            if (starts.every() == null) {
                continue;
            }
            Entry entry = entry(step.code());
            Instant from = entry.lastTick() != null ? entry.lastTick() : entry.since();
            StepClock.Ticks missed = StepClock.between(starts.every(), from, now);
            if (missed.count() == 0) {
                continue;
            }
            if (entry.paused()) {
                advance(step.code(), missed.last(), null);
                continue;
            }
            runs.act(system, PROCESS, "missed", step.code() + "/" + missed.last(), null,
                    missed.count() + (missed.count() >= StepClock.COUNTED ? " or more" : "")
                            + " ticks missed while no node ran since " + from + ", "
                            + (starts.missed() == TenantSpec.Starts.Missed.ONCE
                                    ? "run once for the last of them"
                                    : "skipped to the next"));
            String key = null;
            if (starts.missed() == TenantSpec.Starts.Missed.ONCE) {
                key = surface.startedOn(step, "tick/" + missed.last(), Map.of(), system, null)
                        .key();
            }
            missed(step.code(), missed.last(), key, missed.count());
        }
    }

    /**
     * A run of a step now, outside its occasion, by somebody: the next tick
     * comes when it was due.
     */
    Run now(String code, Map<String, RunSlot> inputs, String by) {
        TenantSpec.Step step = declared(code);
        Run run = surface.startedOn(step, "now/" + UuidV7.newId(), inputs, system, by);
        started(code, run.key());
        return run;
    }

    /** Pauses a step: nothing starts on its occasions until it is resumed. */
    Run pause(String code, String by, String because) {
        declared(code);
        paused(code, true);
        return runs.act(system, PROCESS, "pause", code + "/" + UuidV7.newId(), by, because);
    }

    /** Resumes a step; the ticks that came while it was paused were not missed. */
    Run resume(String code, String by, String because) {
        declared(code);
        paused(code, false);
        return runs.act(system, PROCESS, "resume", code + "/" + UuidV7.newId(), by, because);
    }

    /** A step that starts itself here, or a refusal naming those that do. */
    TenantSpec.Step declaredHere(String code) {
        return declared(code);
    }

    private TenantSpec.Step declared(String code) {
        TenantSpec.Step step = steps.get(code);
        if (step == null) {
            throw new IllegalArgumentException("'" + code + "' is not a step that starts "
                    + "itself here; these are: " + steps.keySet());
        }
        return step;
    }

    /**
     * The runs each step started that are over and older than the step
     * keeps them, removed — the history kept under the retention the step
     * declared — and how many, counted in the step's current entry; an act per pass would
     * regrow the history.
     *
     * <p>Removed whole, history and links with them, as a retention removes
     * records: a run kept as an empty tombstone would still be one per tick.
     *
     * @return how many runs went
     */
    int letGo() {
        int removed = 0;
        Instant now = clock.instant();
        String tables = cloud.jengu.dbo.core.api.Domains.tables(
                cloud.jengu.dbo.work.WorkModel.DOMAIN);
        String history = cloud.jengu.dbo.core.api.Domains.historyTables(
                cloud.jengu.dbo.work.WorkModel.DOMAIN);
        for (TenantSpec.Step step : steps.values()) {
            cloud.jengu.dbo.core.process.StepId id =
                    cloud.jengu.dbo.core.process.StepId.of(step.code());
            List<String> over = runs.over(system, id.processId(), id.step(),
                    now.minus(step.starts().keep()), 500);
            if (over.isEmpty()) {
                continue;
            }
            try (Connection c = database.getConnection()) {
                c.setAutoCommit(false);
                try {
                    for (String sql : List.of(
                            "DELETE FROM %s_history WHERE id = ANY(?::uuid[])".formatted(history),
                            "DELETE FROM %s_identifier WHERE object_id = ANY(?::uuid[])"
                                    .formatted(tables),
                            "DELETE FROM %s_reference WHERE owner_id = ANY(?::uuid[])"
                                    .formatted(tables),
                            "DELETE FROM %s_data WHERE id = ANY(?::uuid[])".formatted(tables))) {
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            ps.setArray(1, c.createArrayOf("text", over.toArray()));
                            ps.executeUpdate();
                        }
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                throw new IllegalStateException(tenant + ": the runs " + step.code()
                        + " started could not be let go", e);
            }
            removed += over.size();
            entry(step.code());
            update(step.code(), """
                    UPDATE state.step_current SET removed = removed + ?, updated_at = now()
                    WHERE step = ?""", (long) over.size(), step.code());
        }
        return removed;
    }

    /** What each self-starting step here is doing. */
    List<Status> status() {
        List<Status> all = new ArrayList<>();
        Instant now = clock.instant();
        for (TenantSpec.Step step : steps.values()) {
            TenantSpec.Starts starts = step.starts();
            Entry entry = entry(step.code());
            Optional<Run> last = entry.lastRun() == null ? Optional.empty()
                    : runs.byKey(entry.lastRun());
            all.add(new Status(step.code(), starts, entry.paused(), entry.lastTick(),
                    entry.lastRun(), last.map(run -> run.status().name()).orElse(null),
                    entry.lastStarted(),
                    starts.every() == null || entry.paused() ? null
                            : StepClock.next(starts.every(), now),
                    entry.missed(), entry.skipped(), entry.started(),
                    entry.removed()));
        }
        return all;
    }

    @Override
    public void close() {
        StepScheduler.Clock open = ticking;
        if (open != null) {
            open.close();
        }
    }

    /**
     * One self-starting step, as a deployment shows its scheduled work.
     *
     * @param next    when its next tick is due, or null where it has no clock
     *                or is paused
     * @param missed  ticks missed while no node ran, all told
     * @param skipped of those, how many the step's declaration skipped
     * @param started how many runs it has started, all told
     * @param removed how many of them were let go under its retention
     */
    record Status(String step, TenantSpec.Starts starts, boolean paused, Instant lastTick,
            String lastRun, String lastOutcome, Instant lastStarted, Instant next,
            long missed, long skipped, long started, long removed) {
    }

    // -- the current entries -------------------------------------------------

    private record Entry(boolean paused, Instant since, Instant lastTick, String lastRun,
            Instant lastStarted, long missed, long skipped, long started, long removed) {
    }

    private void ensureTable() {
        sql("""
                CREATE TABLE IF NOT EXISTS state.step_current (
                  step         text        PRIMARY KEY,
                  paused       boolean     NOT NULL DEFAULT false,
                  since        timestamptz NOT NULL,
                  last_tick    timestamptz,
                  last_run     text,
                  last_started timestamptz,
                  missed       bigint      NOT NULL DEFAULT 0,
                  skipped      bigint      NOT NULL DEFAULT 0,
                  started      bigint      NOT NULL DEFAULT 0,
                  removed      bigint      NOT NULL DEFAULT 0,
                  updated_at   timestamptz NOT NULL DEFAULT now()
                )""");
    }

    private Entry entry(String code) {
        Timestamp now = Timestamp.from(clock.instant());
        try (Connection c = database.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO state.step_current (step, since) VALUES (?, ?)
                    ON CONFLICT (step) DO NOTHING""")) {
                ps.setString(1, code);
                ps.setTimestamp(2, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT paused, since, last_tick, last_run, last_started, missed, skipped,
                           started, removed
                      FROM state.step_current WHERE step = ?""")) {
                ps.setString(1, code);
                try (ResultSet row = ps.executeQuery()) {
                    row.next();
                    return new Entry(row.getBoolean(1), instant(row.getTimestamp(2)),
                            instant(row.getTimestamp(3)), row.getString(4),
                            instant(row.getTimestamp(5)), row.getLong(6), row.getLong(7),
                            row.getLong(8), row.getLong(9));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(tenant + ": where step " + code + " stands could "
                    + "not be read", e);
        }
    }

    /** A tick taken: the latest one only ever moves forward. */
    private void advance(String code, Instant at, String run) {
        update(code, """
                UPDATE state.step_current SET
                  last_tick = GREATEST(last_tick, ?::timestamptz),
                  started = started + CASE WHEN ?::text IS NOT NULL
                    AND last_run IS DISTINCT FROM ?::text THEN 1 ELSE 0 END,
                  last_run = COALESCE(?::text, last_run),
                  last_started = CASE WHEN ?::text IS NULL THEN last_started ELSE now() END,
                  updated_at = now()
                WHERE step = ?""", Timestamp.from(at), run, run, run, run, code);
    }

    private void missed(String code, Instant last, String run, long count) {
        update(code, """
                UPDATE state.step_current SET
                  last_tick = GREATEST(last_tick, ?::timestamptz),
                  started = started + CASE WHEN ?::text IS NOT NULL
                    AND last_run IS DISTINCT FROM ?::text THEN 1 ELSE 0 END,
                  last_run = COALESCE(?::text, last_run),
                  last_started = CASE WHEN ?::text IS NULL THEN last_started ELSE now() END,
                  missed = missed + ?,
                  skipped = skipped + ?,
                  updated_at = now()
                WHERE step = ?
                  -- Two nodes opening at once reckon the same ticks: the first
                  -- to say so counts them, and the second finds them taken.
                  AND (last_tick IS NULL OR last_tick < ?::timestamptz)""",
                Timestamp.from(last), run, run, run, run, count,
                run == null ? count : 0L, code, Timestamp.from(last));
    }

    private void started(String code, String run) {
        entry(code);
        update(code, """
                UPDATE state.step_current SET
                  started = started + CASE WHEN ?::text IS NOT NULL
                    AND last_run IS DISTINCT FROM ?::text THEN 1 ELSE 0 END,
                  last_run = ?, last_started = now(), updated_at = now()
                WHERE step = ?""", run, run, run, code);
    }

    private void paused(String code, boolean paused) {
        entry(code);
        // Resuming moves the last tick to now, so the ticks that came while
        // it was paused are not reckoned as missed the next time a node opens.
        update(code, """
                UPDATE state.step_current SET paused = ?,
                  last_tick = CASE WHEN ?::boolean THEN last_tick ELSE GREATEST(last_tick, ?::timestamptz) END,
                  updated_at = now()
                WHERE step = ?""", paused, paused, Timestamp.from(clock.instant()), code);
    }

    private void update(String code, String statement, Object... values) {
        try (Connection c = database.getConnection();
                PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(tenant + ": where step " + code + " stands could "
                    + "not be written", e);
        }
    }

    private void sql(String statement) {
        try (Connection c = database.getConnection();
                PreparedStatement ps = c.prepareStatement(statement)) {
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(tenant + ": where its self-starting steps stand "
                    + "cannot be kept", e);
        }
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
