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
 * here is written, and a verb is never refused for anything this does.
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
    private final ContactListener.Worker worker;

    ContactLane(Lane lane, Contacts contacts, Runs runs, String client) {
        this.lane = lane;
        this.contacts = contacts;
        this.runs = runs;
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
        return lane.inputs(run);
    }

    @Override
    public SealedWork sealed(Run run) {
        return lane.sealed(run);
    }

    @Override
    public SealedWork sealed(Run run, List<String> recipients) {
        return lane.sealed(run, recipients);
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
