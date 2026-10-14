package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.work.Run;

import java.util.Map;

/**
 * One run's work, arrived whole (REQ-DBO-PROC-INPUTS-ARRIVE-WITH-THE-WORK): the run,
 * its inputs, and the way to say "still moving".
 *
 * @param tenant whose work this is. One step's service may be reached for
 *               every tenant in the fleet — a step the DEPLOYMENT declared is
 *               performed once per tenant that authored a run of it — so the
 *               tenant is an argument rather than configuration. For a step a
 *               single tenant declared it is always that tenant, which is why
 *               it went unsaid for as long as there was only one level
 * @param run    the claimed run — the global truth this work answers to
 * @param inputs the run's declared inputs, resolved and slot-keyed. A
 *               service never fetches, and cannot: the step's declaration
 * is the API it joined, the run fills those slots
 *, and a runner has no verb that takes a reference —
 *               which is what closes the door on asking for data the step
 *               was never entitled to.
 */
public record Work(String tenant, Run run, Map<String, java.util.List<StoredObject>> inputs,
        Progress progress, Run partOf, Secrets secrets) {

    /** How a step reaches a secret of the tenant's, through the lane it was handed. */
    @FunctionalInterface
    public interface Secrets {
        byte[] reach(String name);
    }

    public Work {
        inputs = Map.copyOf(inputs);
    }

    /** Work whose step reaches no secret. */
    public Work(String tenant, Run run, Map<String, java.util.List<StoredObject>> inputs,
            Progress progress, Run partOf) {
        this(tenant, run, inputs, progress, partOf, null);
    }

    /**
     * A secret of the tenant's — a connection's password — by the name the
     * step declares it under.
     *
     * @throws IllegalStateException when the step does not declare it, the
     *                               tenant holds none by that name, or it
     *                               cannot reach this participant sealed
     */
    public String secret(String name) {
        if (secrets == null) {
            throw new IllegalStateException("this work was handed no way to reach a secret");
        }
        return new String(secrets.reach(name), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Work that is a part of nothing a step needs to read. */
    public Work(String tenant, Run run, Map<String, java.util.List<StoredObject>> inputs,
            Progress progress) {
        this(tenant, run, inputs, progress, null);
    }

    /**
     * The run this work is a part of, as it stands — lasting work's request
     * in its inputs and its stage as its milestone — or empty where it is a
     * part of none.
     */
    public java.util.Optional<Run> parent() {
        return java.util.Optional.ofNullable(partOf);
    }

    /**
     * The object in a slot that holds one.
     *
     * <p>Which is most slots, and why this exists: a step declaring
     * {@code Reference(Patient)} should not have to say "the first of them",
     * because there is no first — there is one, and the declaration said so.
     *
     * <p>Refused rather than returning the first when there are several. A
     * step written for one slot and handed a repeating one would otherwise
     * process a single member and report success over the rest.
     */
    public StoredObject input(String slot) {
        java.util.List<StoredObject> held = inputs.get(slot);
        if (held == null || held.isEmpty()) {
            throw new IllegalStateException("this run filled no slot '" + slot + "'; it filled: "
                    + inputs.keySet());
        }
        if (held.size() != 1) {
            throw new IllegalStateException("slot '" + slot + "' holds " + held.size()
                    + " objects and was asked for one — a repeating slot is read with all()");
        }
        return held.get(0);
    }

    /** Every object in a slot, in the order the run was given them. */
    public java.util.List<StoredObject> all(String slot) {
        return inputs.getOrDefault(slot, java.util.List.of());
    }

    /**
     * Progress extends the claim — by evidence, never by tick. A checkpoint
     * carries counts because what a deadline protects against is a process
     * that is alive and getting nowhere, and counts are how "getting
     * somewhere" is said on the record.
     *
     * <p>Two methods, both abstract, on purpose: a default degrading
     * {@link #milestone} to a bare checkpoint would let a decorator drop the
     * one thing the report said while passing every test — the same trap
     * {@code ReadOnce} fell into with the payload overload.
     */
    public interface Progress {

        void checkpoint(Map<String, Long> counts);

        /**
         * The same, also naming the declared point reached — "parsed",
         * "validated", "signed". The store derives the position over the
         * step's declared order; the service asserts only the name.
         */
        void milestone(String milestone, Map<String, Long> counts);
    }
}
