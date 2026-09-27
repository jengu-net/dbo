package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.spring.server.DboFleetStep;
import cloud.jengu.dbo.work.FleetWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One step, performed for every tenant in the world at once.
 *
 * <p>The mirror of the worker sample's {@code AdmittingAPatient}, and the
 * difference between them is the whole of what a fleet step is. That one is a
 * <b>tenant's</b> step: the hospital declared it, a participant enrolled with
 * the hospital polls for it, and it is performed for that tenant only. This
 * one is the <b>deployment's</b>: {@code mom.json} declares it, every tenant's
 * work is offered into its queue, and one bean answers for all of them.
 *
 * <p>Which is why nothing here names a tenant, and why the tenant arrives as
 * an argument rather than as configuration. A tenant joining the world is a
 * file appearing in a directory; it must not also be a release of this.
 *
 * <p><b>Nothing here constructs anything.</b> No consumer, no durable layer,
 * no pool, no queue — the container builds those from the declaration, finds
 * this bean because it says which step it performs, and hands it work. The
 * annotation carries what the code cannot supply and a run has to record:
 * who performed it, which behaviour that is, and whose code it is.
 */
@Component
@DboFleetStep(name = "sample-directory-checker", version = "1.0",
        provider = "cloud.jengu.dbo.samples")
public final class CheckingTheDirectory implements FleetWork.Performer {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.sample.fleet");

    @Override
    public String step() {
        return "fleet.directory.check";
    }

    @Override
    public void perform(String tenant, String step, String runId, String runKey,
            Map<String, java.util.List<cloud.jengu.dbo.core.api.StoredObject>> inputs,
            FleetWork.Reporting reporting) {
        // The tenant is told, not asked about. A fleet step is handed the code
        // of whichever tenant authored the run, and there is no list of them
        // anywhere in this application.
        //
        // THE THREE SHAPES A SLOT CAN BE, and one step declaring all of them
        // is the point of the example rather than a realistic step.

        // Reference(Organization) — the reference is how whoever asked for
        // this run named the hospital's own record without holding it, without
        // being entitled to read it and without sending it. What arrives here
        // is the organisation itself, resolved by the store against the hold
        // this performer took. There is nothing to fetch and nowhere to fetch
        // it from: this application runs outside the store and has no verb
        // that takes a reference.
        byte[] held = FleetWork.one(inputs, "org").payload();
        mustBe("Organization", held, "org");

        // Organization — given with the run. It has no record here, no id and
        // no version: it is a proposal somebody sent, and comparing it against
        // what the tenant holds is the whole of what this step is for.
        byte[] proposed = FleetWork.one(inputs, "proposed").payload();
        mustBe("Organization", proposed, "proposed");

        // Basic[] — several, given. Order is the order they were sent in,
        // because a list somebody sent is a list they meant.
        java.util.List<cloud.jengu.dbo.core.api.StoredObject> notes =
                inputs.getOrDefault("notes", java.util.List.of());
        notes.forEach(note -> mustBe("Basic", note.payload(), "notes"));

        LOG.info("checking the directory: tenant={} run={} held={}B proposed={}B notes={}",
                tenant, runKey, held.length, proposed.length, notes.size());

        // The report goes back through that tenant's OWN lane, so it meets the
        // rules an outcome from a participant on a port meets — including
        // whether a machine may close this step at all. The counts are
        // evidence rather than a heartbeat: they say what was read, in
        // something somebody can act on.
        reporting.closed(Map.of(
                "checked", 1L,
                "notes", (long) notes.size(),
                "bytes", (long) (held.length + proposed.length)));
    }

    /**
     * That the slot carried what the step declared.
     *
     * <p>Belt and braces: the door already refused anything else, and a step
     * that trusts the door and is wrong about it fails somewhere further away.
     * Throwing releases the run with the reason, and a later cycle may take it
     * again.
     */
    private static void mustBe(String type, byte[] payload, String slot) {
        String json = new String(payload, StandardCharsets.UTF_8);
        if (!json.contains("\"resourceType\":\"" + type + "\"")) {
            throw new IllegalStateException("slot '" + slot + "' did not carry a " + type);
        }
    }
}
