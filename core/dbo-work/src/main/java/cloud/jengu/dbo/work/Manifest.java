package cloud.jengu.dbo.work;

import java.util.List;
import java.util.Map;

/**
 * The readable half of work in flight: what a carrier needs to route it and
 * nothing a carrier could read.
 *
 * <p>Tenant, step, the task, and <em>references</em> to the documents the
 * work names — a slot and a {@code Type/id} each. A router reads this and
 * decides where the work goes; a fleet serving every tenant sees, across
 * tenants, which tenant has which task naming which documents, and that is
 * the whole of what it sees. That is a chosen position rather than a leak:
 * the manifest is what routing is made of, and the documents themselves
 * travel beside it sealed.
 *
 * @param tenant     whose work it is
 * @param step       the step the run is of
 * @param run        the run's key, which is what the trail is rooted in
 * @param inputs     slot to reference, exactly as the run names them
 * @param recipients the participants the payload beside this is wrapped to
 * @param head       the head of the run's chain as the store holds it, which the next link commits to
 */
public record Manifest(String tenant, String step, String run, Map<String, String> inputs,
        List<String> recipients, String head) {

    public Manifest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }
}
