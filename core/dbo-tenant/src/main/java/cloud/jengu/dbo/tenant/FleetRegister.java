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
     * @param type     what fills it, in the form the step declared — so
     *                 {@code Reference(Organization)} is one of THIS tenant's
     *                 Organizations being opened, and a bare
     *                 {@code Organization} is one handed to the step with the
     *                 run and never held here. A tenant deciding about a row
     *                 is deciding about its own data, and the two cases are
     *                 not the same decision
     * @param required whether this tenant may decline the step at all
     * @param posture  what happens to work whose processing is not yet
     *                 authorised
     * @param authority whose process it is: the service provider's, which the
     *                 tenant agreed to by joining and may decline unless it is
     *                 required, a jurisdiction's, which binds it, or the
     *                 tenant's own, handed to the provider to carry out
     * @param by       the zone whose process it is, or null for the provider,
     *                 of which a deployment has one
     * @param subProcessors the partners the provider's process is carried out
     *                 by on its behalf, named so that the tenant knows who
     *                 handles its data; empty when the provider does it itself
     */
    public record Row(String step, String slot, String type, boolean required,
            TenantSpec.FleetStep.Posture posture, Authority authority, String by,
            List<String> subProcessors) {

        public Row {
            if (authority == null) {
                throw new IllegalArgumentException(step + ": a row names whose process it is");
            }
            if ((authority == Authority.ZONE) != (by != null)) {
                throw new IllegalArgumentException(step + ": a zone's row names the zone, and "
                        + "the provider's names none");
            }
            subProcessors = List.copyOf(subProcessors);
        }

        /** The provider's own process, carried out by nobody else. */
        public Row(String step, String slot, String type, boolean required,
                TenantSpec.FleetStep.Posture posture) {
            this(step, slot, type, required, posture, Authority.PROVIDER, null, List.of());
        }

        /**
         * This row as one value, which is what a tenant authorises.
         *
         * <p><b>Per row, because a brand-new row and a widened one are
         * different acts.</b> A tenant authorises a whole register in one act —
         * it writes down every row it read — and the store can still say which
         * single row is unapproved, so a deployment may halt for a new row
         * without stopping everything else.
         *
         * <p>Over every field a tenant would decide on, the posture included.
         * A digest that ignored the posture would let a deployment move a row
         * from <i>not until approved</i> to <i>processed and named</i> while the
         * tenant's copy still matched, which is precisely how a deployment
         * would approve its own widening.
         */
        public String digest() {
            // The provider's own row is written as it always was, so that what
            // a tenant authorised before rows named their authority still
            // matches; anything a row says beyond that is appended, and so
            // moves the digest.
            StringBuilder canonical = new StringBuilder(step + '\u0000' + slot + '\u0000'
                    + type + '\u0000' + required + '\u0000' + posture);
            if (authority == Authority.ZONE) {
                canonical.append("\u0000zone\u0000").append(by);
            }
            if (authority == Authority.TENANT) {
                canonical.append("\u0000handed");
            }
            if (!subProcessors.isEmpty()) {
                canonical.append("\u0000sub\u0000")
                        .append(String.join(",", new java.util.TreeSet<>(subProcessors)));
            }
            return digestOver(canonical.toString());
        }
    }

    /** Whose process a row is. */
    public enum Authority {
        /** The service provider's: what the deployment does for every tenant. */
        PROVIDER,
        /** A jurisdiction's: what a zone above the tenant obliges or permits. */
        ZONE,
        /** The tenant's own work, which it hands to the provider's people to carry out. */
        TENANT
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
        rows.forEach(row -> canonical.append(row.digest()).append('\n'));
        return digestOver(canonical.toString());
    }

    /** Which rows this tenant has not authorised: the new ones and the widened. */
    public static List<Row> unapproved(List<Row> rows, Set<String> authorised) {
        return rows.stream().filter(row -> !authorised.contains(row.digest())).toList();
    }

    private static String digestOver(String canonical) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Short, because these are written into a tenant's own declaration
            // by hand as often as by a tool, and a file nobody can read is a
            // file nobody checks. Sixteen characters of SHA-256 is far more
            // than enough to tell two rows of one register apart.
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of the platform", impossible);
        }
    }

    /**
     * The rows the jurisdictions above a tenant put on its register: one per
     * process each zone permits, nearest zone first.
     *
     * <p><b>Required, and applied.</b> A jurisdiction's process is an
     * obligation of membership rather than an offer, so it cannot be declined
     * and nothing waits on its approval; it is on the register so that the
     * tenant reads, in one place, everyone whose process may touch its data.
     * Each zone of the chain is named, because each is a jurisdiction whose
     * rules apply, and a tenant in Estonia is bound by the EU's as well.
     */
    public static List<Row> ofZones(List<String> chain,
            java.util.function.Function<String, Set<String>> permitted) {
        List<Row> rows = new ArrayList<>();
        for (String zone : chain) {
            for (String process : new java.util.TreeSet<>(permitted.apply(zone))) {
                rows.add(new Row(process, null, null, true,
                        TenantSpec.FleetStep.Posture.APPLIED, Authority.ZONE, zone, List.of()));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The rows a tenant's own declaration puts on its register: one for each
     * step whose work it hands to the provider, over the request that work
     * fulfils.
     *
     * <p><b>Not until approved, and never required.</b> Unlike a provider's
     * row, it stands only once the tenant has authorised it, because handing
     * work over is not what joining agreed to. Removing the line that
     * declared it declines it.
     */
    public static List<Row> handed(List<TenantSpec.Step> declared) {
        List<Row> rows = new ArrayList<>();
        for (TenantSpec.Step step : declared) {
            if (step.handsToProvider()) {
                java.util.Map.Entry<String, String> request =
                        step.slots().entrySet().iterator().next();
                rows.add(new Row(step.code(), request.getKey(), request.getValue(), false,
                        TenantSpec.FleetStep.Posture.NOT_UNTIL_APPROVED, Authority.TENANT,
                        null, List.of()));
            }
        }
        return List.copyOf(rows);
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
                    rows.add(new Row(step.code(), slot, type, step.required(), step.posture(),
                            Authority.PROVIDER, null,
                            List.copyOf(new java.util.TreeSet<>(step.delegatesTo()))));
                }
            });
        }
        return List.copyOf(rows);
    }
}
