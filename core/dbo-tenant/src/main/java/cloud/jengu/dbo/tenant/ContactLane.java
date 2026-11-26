package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.Wakeups;
import cloud.jengu.dbo.work.ContactListener;
import cloud.jengu.dbo.work.Contacts;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunChain;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.SealedPayload;
import cloud.jengu.dbo.work.SealedWork;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A tenant's lane, heard by the node it was built on.
 *
 * <p>Every verb is the lane's own; what this adds is that the node notices
 * who asked, for which step, and tells whoever listens to that step. Nothing
 * here is written for that, and a verb is never refused for it.
 *
 * <p><b>On a place, a run is not handed over before what it names is here.</b>
 * A record the place let go of because its tenant holds it is brought back
 * by the place's next round, or the run ends failed there saying which. Work
 * asked for in between is held back until that round, saying what it waits
 * for, rather than handed to a step with a slot it cannot fill: a step that
 * throws over it gives the run to people, where it waits for good after the
 * record is back.
 *
 * <p><b>A verb about a run counts for the run's step once it has been
 * answered</b>, read from the run as the store holds it: the body's process
 * and step are the asker's words, and a run this worker could act on is the
 * one fact about it the store has checked. A poll counts for each step it
 * names.
 */
final class ContactLane implements Lane {

    private final Lane lane;
    private final Contacts contacts;
    private final Runs runs;
    private final Declarations declarations;
    private final int limit;
    private final ContactListener.Worker worker;
    private final java.util.function.BooleanSupplier place;
    private final Duration round;

    /** A lane on a node that serves the tenant as no place of it. */
    ContactLane(Lane lane, Contacts contacts, Runs runs, Declarations declarations,
            int limit, String client) {
        this(lane, contacts, runs, declarations, limit, client, () -> false, Duration.ZERO);
    }

    /**
     * @param limit how large a heartbeat's statistics may be, in bytes of
     *              their JSON
     * @param place whether this node serves the tenant as a place of it
     * @param round how long the place's rounds are apart: how long work waits
     *              for a record it names to be brought back
     */
    ContactLane(Lane lane, Contacts contacts, Runs runs, Declarations declarations,
            int limit, String client, java.util.function.BooleanSupplier place,
            Duration round) {
        this.lane = lane;
        this.contacts = contacts;
        this.runs = runs;
        this.declarations = declarations;
        this.limit = limit;
        this.place = place;
        this.round = round;
        this.worker = new ContactListener.Worker(client, lane.identity().name(),
                lane.identity().version());
    }

    private void heard(Set<String> steps) {
        contacts.heard(lane.tenant(), steps, worker, null);
    }

    private void heard(Run run) {
        if (run != null) {
            heard(Set.of(run.process() + "." + run.step()));
        }
    }

    /** The run as the store holds it, read only when somebody listens. */
    private void heardAbout(Run asked) {
        if (contacts.listening()) {
            runs.byKey(asked.key()).ifPresent(this::heard);
        }
    }

    @Override
    public String tenant() {
        return lane.tenant();
    }

    @Override
    public Executor identity() {
        return lane.identity();
    }

    @Override
    public List<Run> poll(Set<String> steps, int limit) {
        List<Run> offered = lane.poll(steps, limit);
        heard(steps);
        return offered;
    }

    @Override
    public Optional<Wakeups> wakeups() {
        return lane.wakeups();
    }

    @Override
    public Optional<Run> claim(Run run, Duration holdFor) {
        Optional<Run> claimed = lane.claim(run, holdFor);
        claimed.ifPresent(this::heard);
        return claimed;
    }

    @Override
    public Run checkpoint(Run run, Map<String, Long> counts, Duration holdFor) {
        Run held = lane.checkpoint(run, counts, holdFor);
        heard(held);
        return held;
    }

    @Override
    public Run milestone(Run run, String milestone, Map<String, Long> counts,
            Duration holdFor) {
        Run held = lane.milestone(run, milestone, counts, holdFor);
        heard(held);
        return held;
    }

    @Override
    public void released(Run run, String reason, Failure failure) {
        lane.released(run, reason, failure);
        heardAbout(run);
    }

    @Override
    public byte[] secret(Run run, String name) {
        byte[] value = lane.secret(run, name);
        heardAbout(run);
        return value;
    }

    @Override
    public cloud.jengu.dbo.work.SealedPayload sealedSecret(Run run, String name) {
        cloud.jengu.dbo.work.SealedPayload sealed = lane.sealedSecret(run, name);
        heardAbout(run);
        return sealed;
    }

    @Override
    public Run refused(Run run, String because) {
        Run ended = lane.refused(run, because);
        heardAbout(run);
        return ended;
    }

    @Override
    public void closed(Run run) {
        lane.closed(run);
        heardAbout(run);
    }

    @Override
    public void closed(Run run, String head) {
        lane.closed(run, head);
        heardAbout(run);
    }

    @Override
    public Run committed(Run run, String head, List<Outcome.Write> result) {
        Run ended = lane.committed(run, head, result);
        heard(ended);
        return ended;
    }

    @Override
    public Run committed(Run run, String head, List<Outcome.Write> result,
            List<Outcome.Part> parts) {
        Run ended = lane.committed(run, head, result, parts);
        heard(ended);
        return ended;
    }

    @Override
    public Run committed(Run run, String head, List<Outcome.Write> result,
            List<Outcome.Part> parts, Outcome.Said said) {
        Run ended = lane.committed(run, head, result, parts, said);
        heard(ended);
        return ended;
    }

    @Override
    public java.util.Optional<Run> partOf(Run run) {
        return lane.partOf(run);
    }

    @Override
    public void reopen(Run run, String because) {
        lane.reopen(run, because);
    }

    @Override
    public int releaseLapsed() {
        return lane.releaseLapsed();
    }

    @Override
    public void declare(Declarations.Declared declared) {
        lane.declare(declared);
    }

    /**
     * Measured before anything else, so a heartbeat over the limit is refused
     * whether or not anybody listens; heard for every listened step this
     * worker declared on this tenant.
     */
    @Override
    public void heartbeat(Map<String, Object> statistics) {
        Map<String, Object> said = statistics == null ? Map.of() : statistics;
        int size = cloud.jengu.dbo.core.wire.RecordWire.write(said)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (size > limit) {
            throw new IllegalStateException(lane.tenant() + ": a heartbeat's statistics are "
                    + size + " bytes, over this node's limit of " + limit + " bytes ("
                    + HEARTBEAT_LIMIT + ")");
        }
        lane.heartbeat(said);
        if (!contacts.listening()) {
            return;
        }
        Set<String> declared = new java.util.LinkedHashSet<>();
        for (String step : contacts.listened()) {
            int dot = step.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            boolean mine = declarations.forStep(step.substring(0, dot), step.substring(dot + 1))
                    .stream().anyMatch(one -> one.name().equals(worker.name()));
            if (mine) {
                declared.add(step);
            }
        }
        contacts.heard(lane.tenant(), declared, worker, said);
    }

    /** The framework property a node's heartbeat limit is read from, in bytes. */
    static final String HEARTBEAT_LIMIT = "dbo.heartbeat.limit";

    /** What a node accepts when nobody said: enough for a worker and what it routes. */
    static final int DEFAULT_HEARTBEAT_LIMIT = 64 * 1024;

    @Override
    public void introduce(StepDeclaration step) {
        lane.introduce(step);
    }

    @Override
    public void withdraw(Declarations.Declared declared) {
        lane.withdraw(declared);
    }

    @Override
    public void routes(List<cloud.jengu.dbo.work.Trackable> behind) {
        lane.routes(behind);
    }

    @Override
    public Map<String, List<StoredObject>> inputs(Run run) {
        Map<String, List<StoredObject>> inputs = lane.inputs(run);
        Set<String> here = new java.util.HashSet<>();
        inputs.values().forEach(held -> held.forEach(object ->
                here.add(object.typeName() + "/" + object.id())));
        waitsFor(run, here);
        return inputs;
    }

    @Override
    public SealedWork sealed(Run run) {
        return sealedFilled(run, lane.sealed(run));
    }

    @Override
    public SealedWork sealed(Run run, List<String> recipients) {
        return sealedFilled(run, lane.sealed(run, recipients));
    }

    private SealedWork sealedFilled(Run run, SealedWork sealed) {
        if (run.crossing() == null) {
            // What came back on a crossing is an answer, not the inputs.
            Set<String> here = new java.util.HashSet<>();
            sealed.payload().forEach(payload -> here.add(payload.reference()));
            waitsFor(run, here);
        }
        return sealed;
    }

    /**
     * On a place, holds the run back until its next round when a record it
     * names is not among what was read for it, and says so to the asker as
     * a run no longer theirs.
     */
    private void waitsFor(Run run, Set<String> here) {
        if (!place.getAsBoolean() || cloud.jengu.dbo.work.WorkModel.authoredElsewhere(run.key())) {
            // Another side's run is that side's to hold back.
            return;
        }
        List<String> missing = new java.util.ArrayList<>();
        run.inputs().forEach((slot, filled) -> {
            if (!filled.referred()) {
                return;
            }
            for (String reference : filled.values()) {
                int slash = reference.indexOf('/');
                if (slash > 0 && reference.indexOf('/', slash + 1) < 0
                        && !here.contains(reference)) {
                    missing.add(slot + ": " + reference);
                }
            }
        });
        if (missing.isEmpty()) {
            return;
        }
        String waitsFor = "its input " + String.join(", ", missing)
                + ", which this place brings back from its tenant";
        Run current = runs.byKey(run.key()).orElse(run);
        Run held = runs.waiting(current, waitsFor, java.time.Instant.now().plus(round));
        heard(held);
        throw new Runs.NotHeld("run '" + run.key() + "' " + held.statusReason());
    }

    @Override
    public SealedPayload identified(Run run, String reference, String purpose) {
        return lane.identified(run, reference, purpose);
    }

    @Override
    public String opened(Run run, String reference, RunChain.Link link) {
        return lane.opened(run, reference, link);
    }
}
