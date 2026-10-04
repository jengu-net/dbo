package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.samples.worker.RecordingContact;
import cloud.jengu.dbo.work.ContactListener;
import cloud.jengu.dbo.work.RunInitiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Told when this node hears a worker that registers patients, and when it
 * stops.
 *
 * <p>The store decides nothing about contact and keeps none of it. What the
 * clinic wants on record — that a worker came, and that it went quiet — it
 * asks for as work, under a key of its own: the same worker, the same
 * transition in the same minute is one run however many nodes noticed it,
 * and the note it writes is validated and carries that run like any other
 * record.
 *
 * <p>How long a worker may say nothing is the clinic's to choose. Its worker
 * heartbeats every poll, so a silence of several polls is one somebody would
 * want to hear about.
 */
// --8<-- [start:listener]
@Component
public final class NoticingTheWorkers implements ContactListener {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.sample.contact");

    private final RunInitiator initiator;
    private final Duration silence;
    private final List<Noted> noted = new CopyOnWriteArrayList<>();
    private final List<Reset> resets = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> lastSaid =
            new java.util.concurrent.ConcurrentHashMap<>();

    NoticingTheWorkers(RunInitiator initiator,
            @Value("${clinic.contact.silence:PT30S}") Duration silence) {
        this.initiator = initiator;
        this.silence = silence;
    }

    @Override
    public String step() {
        return AskingForARegistration.STEP;
    }

    @Override
    public Duration silence() {
        return silence;
    }

    @Override
    public void appeared(Appeared appeared) {
        if (appeared.statistics() != null) {
            lastSaid.put(appeared.tenant() + "/" + appeared.worker().name(),
                    appeared.statistics());
        }
        note(appeared.tenant(), appeared.worker(), "appeared", appeared.node(),
                appeared.since());
    }

    /** Kept for a screen, never written: what each worker last said about itself. */
    @Override
    public void statistics(Statistics statistics) {
        lastSaid.put(statistics.tenant() + "/" + statistics.worker().name(),
                statistics.statistics());
    }

    @Override
    public void unknown(Unknown unknown) {
        lastSaid.remove(unknown.tenant() + "/" + unknown.worker().name());
        note(unknown.tenant(), unknown.worker(), "unknown", unknown.node(), unknown.lastSeen());
    }

    @Override
    public void reset(Reset reset) {
        resets.add(reset);
        LOG.info("contact reset: step={} node={}", reset.step(), reset.node());
    }

    private void note(String tenant, Worker worker, String transition, String node,
            Instant at) {
        String key = String.join("-", worker.name(), worker.version(), transition,
                String.valueOf(at.truncatedTo(ChronoUnit.MINUTES).getEpochSecond()));
        String note = """
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"contact with a worker"},
                 "effectiveDateTime":"%s",
                 "valueString":"%s %s %s on %s"}""".formatted(at, worker.name(),
                worker.version(), transition, node);
        RunInitiator.Started started = initiator.starting(tenant, RecordingContact.STEP,
                Map.of("note", RunInitiator.Slot.object(note)), key);
        noted.add(new Noted(tenant, worker, transition, started));
    }

    /** One transition this application asked to have written down, and the run it started. */
    public record Noted(String tenant, Worker worker, String transition,
            RunInitiator.Started started) {
    }

    /** What this application has asked to have written down, oldest first. */
    public List<Noted> noted() {
        return List.copyOf(noted);
    }

    /** What a worker said in its last heartbeat while this node heard it, if it is heard. */
    public java.util.Optional<Map<String, Object>> lastSaid(String tenant, String worker) {
        return java.util.Optional.ofNullable(lastSaid.get(tenant + "/" + worker));
    }

    /** Each node's word that everything about this step is unknown there. */
    public List<Reset> resets() {
        return List.copyOf(resets);
    }
}
// --8<-- [end:listener]
