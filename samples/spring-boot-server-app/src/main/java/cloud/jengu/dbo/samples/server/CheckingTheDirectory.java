package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.runner.FleetStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * One step, performed for every tenant in the world at once.
 *
 * <p>The mirror of the worker sample's {@code AdmittingAPatient}, and the
 * difference between them is the whole of what a fleet step is — but it is not
 * a difference in the INTERFACE. That one is a <b>tenant's</b> step: the
 * hospital declared it, a participant enrolled with the hospital polls for it,
 * and it is performed for that tenant only. This one is the
 * <b>deployment's</b>: {@code mom.json} declares it, every tenant's work is
 * offered into its queue, and one bean answers for all of them.
 *
 * <p>Both are a {@code StepService}. Which level declared the code is what
 * decides who offers the work, and a step code belongs to exactly one level —
 * so the store always knows, and this never has to say.
 *
 * <p>Which is also why the tenant arrives as {@code work.tenant()} rather than
 * as configuration. A tenant joining the world is a file appearing in a
 * directory; it must not also be a release of this.
 *
 * <p><b>Nothing here constructs anything.</b> No consumer, no durable layer,
 * no pool, no queue — the container builds those from the declaration, finds
 * this bean because it says which step it performs, and hands it work. The
 * annotation carries what the code cannot supply and a run has to record: who
 * performed it, which behaviour that is, and whose code it is.
 */
// --8<-- [start:step]
@Component
@FleetStep(name = "sample-directory-checker", version = "1.0",
        provider = "cloud.jengu.dbo.samples")
public final class CheckingTheDirectory implements StepService {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.sample.fleet");

    @Override
    public String step() {
        return "fleet.directory.check";
    }

    @Override
    public Outcome perform(Work work) {
        // THE THREE SHAPES A SLOT CAN BE, and one step declaring all of them is
        // the point of the example rather than a realistic step.

        // Reference(Organization) — the reference is how whoever asked for this
        // run named the hospital's own record without holding it, without being
        // entitled to read it and without sending it. What arrives here is the
        // organisation itself, resolved by the store against the hold this
        // performer took. There is nothing to fetch and nowhere to fetch it
        // from: this application runs outside the store and has no verb that
        // takes a reference.
        byte[] held = work.input("org").payload();

        // Organization — given with the run. It has no record here, no id and
        // no version: it is a proposal somebody sent, and comparing it against
        // what the tenant holds is the whole of what this step is for.
        byte[] proposed = work.input("proposed").payload();

        // Basic[] — several, given. Order is the order they were sent in,
        // because a list somebody sent is a list they meant.
        List<cloud.jengu.dbo.core.api.StoredObject> notes = work.all("notes");

        if (!isA("Organization", held) || !isA("Organization", proposed)
                || notes.stream().anyMatch(note -> !isA("Basic", note.payload()))) {
            // Returning a refusal and throwing are the same thing: the run is
            // released with the reason and a later cycle may take it again.
            return Outcome.failed("a slot did not carry what the step declares");
        }

        // WHOSE DIRECTORY IT IS, told rather than asked about. One of these
        // answers for every tenant, and there is no list of them anywhere in
        // this application.
        LOG.info("checking the directory: tenant={} run={} held={}B proposed={}B notes={}",
                work.tenant(), work.run().key(), held.length, proposed.length, notes.size());

        // Progress is evidence rather than a heartbeat, and the tally lands on
        // the run in the tenant's own store — through that tenant's own lane,
        // so it meets the rules an outcome from a participant on a port meets.
        return Outcome.done(Map.of(
                "checked", 1L,
                "notes", (long) notes.size(),
                "bytes", (long) (held.length + proposed.length)));
    }

    private static boolean isA(String type, byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8)
                .contains("\"resourceType\":\"" + type + "\"");
    }
}
// --8<-- [end:step]
