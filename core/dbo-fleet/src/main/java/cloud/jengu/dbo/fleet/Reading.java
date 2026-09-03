package cloud.jengu.dbo.fleet;

import java.util.List;
import java.util.Map;

/**
 * What the fleet said, node by node, and the one answer derived from it.
 *
 * <p><b>Nothing is omitted for having failed.</b> A node that did not answer
 * is in the reading as unreachable, a tenant the reader holds no credential
 * for is in it as such, and a refusal is in it as a refusal — because a list
 * of the nodes that worked answers "which nodes are fine" while looking like
 * it answered "which nodes exist", and the missing one is the one an operator
 * opened this for.
 *
 * @param nodes   one entry per node the reader was given, in that order
 * @param map     the network map: step id, then version, then the nodes
 *                that have it installed — the union of every node's
 *                inventory, one answer rather than a walk
 */
public record Reading(List<NodeReading> nodes, Map<String, Map<String, List<String>>> map) {

    /** How an ask ended, in one word a caller can switch on. */
    public enum Outcome {
        /** The door answered. */
        ANSWERED,
        /** Nothing listened, or the connection failed or timed out. */
        UNREACHABLE,
        /** The door refused the credential, or the credential could not be minted. */
        REFUSED,
        /** The reader holds no credential for this tenant, so it did not ask. */
        NO_CREDENTIAL,
        /** The node is not serving this tenant, so there is nothing to ask. */
        NOT_SERVING
    }

    /**
     * One node's answers, every one labelled with the node by being here.
     *
     * @param node      the deployment's name for the node
     * @param outcome   how the node-level questions ended
     * @param detail    why, when they did not end in an answer
     * @param tenants   what the node said it is doing about each tenant it
     *                  has been told about, and what each tenant said
     * @param catalogue the steps installed in the node, as the node lists them
     */
    public record NodeReading(String node, Outcome outcome, String detail,
            List<TenantReading> tenants, List<Map<String, Object>> catalogue) {}

    /**
     * One tenant on one node.
     *
     * @param tenant  the tenant's code
     * @param state   what the node is doing about it — serving, coming up,
     *                failed — in the node's own words
     * @param outcome how asking the tenant ended
     * @param detail  why, when it was not an answer
     * @param runs    the run envelopes the tenant's fleet door answered with
     */
    public record TenantReading(String tenant, String state, Outcome outcome, String detail,
            List<Map<String, Object>> runs) {}
}
