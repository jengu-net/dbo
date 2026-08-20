package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.UuidV7;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The runs of one tenant's store (#46).
 *
 * <p>Everything that advances a run goes through here, because every advance is
 * a version, a history link and a feed event: a caller holding a payload and
 * writing it itself would be free to write a heartbeat, and a heartbeat is a
 * feed event per tick for a fact nobody asked about.
 */
public final class Runs {

    private final ObjectStore store;

    public Runs(ObjectStore store) {
        this.store = store;
    }

    /**
     * A pipeline run: one per attempt at a body of work, closing when every
     * item is terminal.
     */
    public Run pipeline(String process, String step) {
        return write(new State(UuidV7.newId(), process, step, RunKind.PIPELINE,
                Holder.AUTOMATION, null, null, Map.of(), null));
    }

    /**
     * The sweep for this scope — found, not started, because the operator's
     * question is about the thing rather than about the pass
     * (REQ-DBO-PROC-RUN-KINDS). One durable run per (process, step, scope),
     * checkpointed.
     */
    public Run sweep(String process, String step, String scope) {
        String key = process + "/" + step + "/" + scope;
        return byKey(key).orElseGet(() -> write(new State(key, process, step, RunKind.SWEEP,
                Holder.AUTOMATION, null, null, Map.of(), null)));
    }

    /** A pass over a sweep: what this round found, and what it therefore closes. */
    public Pass pass(Run sweep) {
        return new Pass(sweep);
    }

    /**
     * One <b>outcome somebody must see</b>, as a child run.
     *
     * <p><b>Children are exceptions, not an enumeration.</b> A run over forty
     * thousand concepts records a tally of forty thousand and three children,
     * because three of them need a person. One child per item processed would
     * make a large run forty thousand records, forty thousand feed events and a
     * history nobody can page through — every advance is a version, a history
     * link and a feed event, and that is the cost of being a record rather than
     * a row. What everything did is the tally's job; what somebody must act on
     * is a child's.
     *
     * <p>The failure class decides who holds it: a record that is wrong is a
     * person's, a store that was unavailable is a retry and nobody's card
     * (REQ-DBO-PROC-ESCALATION-BY-FAILURE-CLASS).
     */
    public Run item(Run parent, String reference, Failure failure, String message) {
        return write(new State(UuidV7.newId(), parent.process(), parent.step(), parent.kind(),
                failure.holder(), parent.key(), parent.correlation(), Map.of(),
                new Run.Item(reference, failure, message)));
    }

    /** A run that succeeded: nothing is owed, and nobody holds it. */
    public Run closed(Run run) {
        return held(run, Holder.NOBODY);
    }

    /** Moves a run to a holder — the only field anybody reads first. */
    public Run held(Run run, Holder holder) {
        return update(run, state(run).withHolder(holder));
    }

    /** A checkpoint: what this run has done so far, counted. */
    public Run tally(Run run, Map<String, Long> counts) {
        return update(run, state(run).withTally(counts));
    }

    /**
     * The correlation this work was handed, echoed and never interpreted
     * (REQ-DBO-PROC-CORRELATION-TRAVELS-OPAQUE).
     */
    public Run correlated(Run run, String correlation) {
        return update(run, state(run).withCorrelation(correlation));
    }

    /** A run by its own key. */
    public Optional<Run> byKey(String key) {
        return store.getByIdentifier(WorkModel.TYPE, List.of(WorkModel.key(key)))
                .stream().findFirst().map(Run::of);
    }

    /** What is waiting for somebody of this kind — the list an operator opens. */
    public List<Run> holding(Holder holder) {
        return store.select(Criteria.of(WorkModel.TYPE)
                        .eq("holder", EnvelopeValue.of(holder.wire()))).stream()
                .map(Run::of).toList();
    }

    /** The items of a run, open and closed. */
    public List<Run> items(Run parent) {
        return store.select(Criteria.of(WorkModel.TYPE)
                        .eq("parent", EnvelopeValue.of(parent.key()))).stream()
                .map(Run::of).toList();
    }

    /**
     * A pass over a sweep, closing by re-evaluation
     * (REQ-DBO-PROC-CLOSE-BY-RE-EVALUATION).
     *
     * <p>What this pass finds wrong stays open; what it does not find again is
     * closed by the pass itself. So a person fixes the world and the next round
     * closes their card — nobody clicks resolved on a fault that is still live.
     */
    public final class Pass {

        private final Run sweep;
        private final Map<String, Run> before = new LinkedHashMap<>();
        private final List<String> seen = new ArrayList<>();
        private final Map<String, Long> counts = new LinkedHashMap<>();

        private Pass(Run sweep) {
            this.sweep = sweep;
            for (Run item : items(sweep)) {
                if (item.item() != null && item.open()) {
                    before.put(item.item().reference(), item);
                }
            }
        }

        /**
         * This item is still wrong, or wrong for the first time.
         *
         * <p>Only what somebody must see: a pass over ten thousand things
         * counts ten thousand and names the handful that need acting on.
         */
        public Pass item(String reference, Failure failure, String message) {
            seen.add(reference);
            Run existing = before.get(reference);
            if (existing == null) {
                Runs.this.item(sweep, reference, failure, message);
            } else if (existing.holder() != failure.holder()) {
                held(existing, failure.holder());
            }
            return this;
        }

        /** A count worth keeping: read, applied, skipped, swept. */
        public Pass counted(String name, long count) {
            counts.put(name, count);
            return this;
        }

        /**
         * Ends the pass: anything not seen again is closed, and the sweep is
         * held by whoever the remaining items say holds it.
         */
        public Run done() {
            before.forEach((reference, item) -> {
                if (!seen.contains(reference)) {
                    closed(item);
                }
            });
            Run current = byKey(sweep.key()).orElse(sweep);
            if (!counts.isEmpty()) {
                current = tally(current, counts);
            }
            boolean anybodyWaiting = items(current).stream()
                    .anyMatch(item -> item.open() && item.needsAPerson());
            // Converged when nothing is left for a person. A sweep is never
            // "finished" — it is either agreeing with the world or not.
            return held(current, anybodyWaiting ? Holder.PERSON : Holder.NOBODY);
        }
    }

    // ------------------------------------------------------------- writing

    private State state(Run run) {
        return new State(run.key(), run.process(), run.step(), run.kind(), run.holder(),
                run.parent(), run.correlation(), run.tally(), run.item());
    }

    private Run write(State state) {
        PutResult result = store.putIfAbsent(IdentityRef.identifier(WorkModel.KEY_SYSTEM,
                state.key()), PutRequest.create(WorkModel.TYPE, state.payload()));
        return byKey(state.key()).orElseThrow(() -> new IllegalStateException(
                "a run was written as " + result.id() + " and cannot be read back"));
    }

    private Run update(Run run, State state) {
        StoredObject stored = store.get(WorkModel.TYPE, run.id()).orElseThrow(
                () -> new IllegalStateException("run " + run.key() + " has gone"));
        store.put(new PutRequest(WorkModel.TYPE, stored.id(), stored.versionId(),
                state.payload()));
        return byKey(state.key()).orElseThrow();
    }

    /** The payload shape, in one place, so no caller authors a run by hand. */
    private record State(String key, String process, String step, RunKind kind, Holder holder,
            String parent, String correlation, Map<String, Long> tally, Run.Item item) {

        State withHolder(Holder holder) {
            return new State(key, process, step, kind, holder, parent, correlation, tally, item);
        }

        State withTally(Map<String, Long> tally) {
            return new State(key, process, step, kind, holder, parent, correlation,
                    Map.copyOf(tally), item);
        }

        State withCorrelation(String correlation) {
            return new State(key, process, step, kind, holder, parent, correlation, tally, item);
        }

        byte[] payload() {
            StringBuilder json = new StringBuilder(256)
                    .append("{\"key\":").append(Json.quoted(key))
                    .append(",\"process\":").append(Json.quoted(process))
                    .append(",\"step\":").append(Json.quoted(step))
                    .append(",\"kind\":").append(Json.quoted(kind.wire()))
                    .append(",\"holder\":").append(Json.quoted(holder.wire()));
            if (parent != null) {
                json.append(",\"parent\":").append(Json.quoted(parent));
            }
            if (correlation != null) {
                json.append(",\"correlation\":").append(Json.quoted(correlation));
            }
            if (!tally.isEmpty()) {
                json.append(",\"tally\":{");
                boolean first = true;
                for (Map.Entry<String, Long> count : tally.entrySet()) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    json.append(Json.quoted(count.getKey())).append(':').append(count.getValue());
                }
                json.append('}');
            }
            if (item != null) {
                json.append(",\"item\":{\"reference\":").append(Json.quoted(item.reference()))
                        .append(",\"failure\":").append(Json.quoted(item.failure().wire()))
                        .append(",\"message\":").append(Json.quoted(item.message()))
                        .append('}');
            }
            return json.append(",\"at\":").append(Json.quoted(java.time.Instant.now().toString()))
                    .append('}').toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
