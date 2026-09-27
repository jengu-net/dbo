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
            Map<String, cloud.jengu.dbo.core.api.StoredObject> inputs,
            FleetWork.Reporting reporting) {
        // The tenant is told, not asked about. A fleet step is handed the code
        // of whichever tenant authored the run, and there is no list of them
        // anywhere in this application.
        //
        // THE OBJECT ARRIVES WHOLE, and this is the half worth looking at. The
        // run named a Reference(Organization) — a reference, because whoever
        // asked for this run need not have held the organisation or been
        // entitled to read it. What arrives here is the organisation itself,
        // resolved by the store against the hold this performer took. There is
        // nothing to fetch and nowhere to fetch it from: this application runs
        // outside the store and has no verb that takes a reference, so a slot
        // delivered as a reference would be a slot it could do nothing with.
        byte[] payload = inputs.get("org").payload();
        String directory = new String(payload, StandardCharsets.UTF_8);
        LOG.info("checking the directory: tenant={} run={} bytes={}",
                tenant, runKey, payload.length);

        if (!directory.contains("\"resourceType\":\"Organization\"")) {
            // Returning a refusal and throwing are the same thing: the run is
            // released with the reason and a later cycle may take it again.
            throw new IllegalStateException("slot 'org' did not carry an Organization");
        }

        // The report goes back through that tenant's OWN lane, so it meets the
        // rules an outcome from a participant on a port meets — including
        // whether a machine may close this step at all. The count is evidence
        // rather than a heartbeat: it says what was read, in something
        // somebody can act on.
        reporting.closed(Map.of("checked", 1L, "bytes", (long) payload.length));
    }
}
