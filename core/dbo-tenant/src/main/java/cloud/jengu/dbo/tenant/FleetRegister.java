package cloud.jengu.dbo.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What a tenant reads before it joins: every payload this deployment opens.
 *
 * <p><b>Derived, never stored.</b> The rows come from the management tenant's
 * declaration and from what this tenant declined, so what the deployment does
 * with data is one document and the register is a reading of it. A register
 * kept as records beside the declaration would be a second place to ask and
 * therefore a second answer, and the first time they disagreed the tenant
 * would have no way to know which was true.
 *
 * <p><b>A row is a SLOT, and only an opened one.</b> A step that reads the
 * envelope and moves the work discloses nothing and is not on the register —
 * which is what makes requiring a router an operational act and requiring a
 * processor something else. The slot is the grain because access is recorded
 * per DOCUMENT: rows and trail entries then line up one to one, and a
 * disagreement between what was declared and what was done is a comparison
 * rather than an investigation.
 *
 * <p><b>It is the deployment's statement, not its permission.</b> Authorisation
 * is the tenant's word — a row is admitted by saying nothing and declined by
 * one line — and the register is what a tenant reads in order to decide.
 */
public final class FleetRegister {

    /**
     * One opened slot, and everything a tenant needs to decide about it.
     *
     * @param step     the step that opens it
     * @param slot     the slot's name, as the step declares it
     * @param type     what fills it
     * @param required whether this tenant may decline the step at all
     * @param posture  what happens to work whose processing is not yet
     *                 authorised
     */
    public record Row(String step, String slot, String type, boolean required,
            TenantSpec.FleetStep.Posture posture) {
    }

    private FleetRegister() {
    }

    /**
     * The whole register as one value, so a tenant sees a change as a
     * comparison rather than an audit.
     *
     * <p><b>This is what a tenant authorises.</b> Enrolment is per tenant and
     * has to be answerable all at once — a tenant approving rows one at a time
     * would be a tenant that can never be sure it has finished, and a
     * deployment that could widen what it opens by adding a row nobody
     * noticed. So the act is: read the register, authorise THIS register, and
     * write down which one it was.
     *
     * <p>Derived from the rows in declaration order, over every field a tenant
     * would care about — so adding a slot, opening one that was only carried,
     * making a step required or changing a posture all move it. A digest that
     * ignored the posture would let a deployment move a row from *not until
     * approved* to *processed and named* without the tenant's copy changing,
     * which is precisely the way a deployment could approve its own widening.
     */
    public static String digestOf(List<Row> rows) {
        StringBuilder canonical = new StringBuilder();
        for (Row row : rows) {
            canonical.append(row.step()).append('\u0000')
                    .append(row.slot()).append('\u0000')
                    .append(row.type()).append('\u0000')
                    .append(row.required()).append('\u0000')
                    .append(row.posture()).append('\n');
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of the platform", impossible);
        }
    }

    /**
     * The rows this tenant would read, from the deployment's declaration and
     * its own.
     *
     * <p>A declined step contributes nothing: from the tenant's side a step it
     * declined and a step the deployment does not perform are one fact, and a
     * register listing rows that will never happen would be a register nobody
     * can act on.
     */
    public static List<Row> of(List<TenantSpec.FleetStep> declared, Set<String> declined) {
        List<Row> rows = new ArrayList<>();
        for (TenantSpec.FleetStep step : declared) {
            if (declined.contains(step.code()) || !step.isProcessor()) {
                continue;
            }
            // Declaration order, and the slots in the order the step declares
            // them, so two readings of one declaration are the same reading —
            // which is what lets a tenant see a change as a comparison.
            step.slots().forEach((slot, type) -> {
                if (step.opens().contains(slot)) {
                    rows.add(new Row(step.code(), slot, type, step.required(), step.posture()));
                }
            });
        }
        return List.copyOf(rows);
    }
}
