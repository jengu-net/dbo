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
     * @param partOf   for a step of the tenant's own a person of the provider
     *                 may start, the handed step whose holder starts it; it
     *                 opens no slot, so the row names none
     * @param purpose  what the row is done for, which a tenant may authorise
     *                 in place of the rows serving it; null for none
     */
    public record Row(String step, String slot, String type, boolean required,
            TenantSpec.FleetStep.Posture posture, Authority authority, String by,
            List<String> subProcessors, String partOf, TenantSpec.Purpose purpose) {

        public Row {
            if (partOf != null && (authority != Authority.TENANT || slot != null)) {
                throw new IllegalArgumentException(step + ": a part a holder of handed work "
                        + "starts is the tenant's own row, and opens no slot");
            }
            if (authority == null) {
                throw new IllegalArgumentException(step + ": a row names whose process it is");
            }
            if ((authority == Authority.ZONE) != (by != null)) {
                throw new IllegalArgumentException(step + ": a zone's row names the zone, and "
                        + "the provider's names none");
            }
            subProcessors = List.copyOf(subProcessors);
        }

        /** A row serving no purpose a declaration named. */
        public Row(String step, String slot, String type, boolean required,
                TenantSpec.FleetStep.Posture posture, Authority authority, String by,
                List<String> subProcessors, String partOf) {
            this(step, slot, type, required, posture, authority, by, subProcessors, partOf,
                    null);
        }

        /**
         * Who handles what this row opens: the provider and the partners it
         * carries its process out with, or the zone whose process it is.
         *
         * <p>Half of what a purpose is agreed to, beside the types it opens. A
         * person deciding about a purpose decides who gets the data and which
         * data, and a step renamed or split changes neither.
         */
        public java.util.SortedSet<String> participants() {
            java.util.SortedSet<String> who = new java.util.TreeSet<>();
            if (authority == Authority.ZONE) {
                who.add(by);
            } else {
                who.add(PROVIDER_PARTICIPANT);
                who.addAll(subProcessors);
            }
            return java.util.Collections.unmodifiableSortedSet(who);
        }

        /** A row that is no part of handed work. */
        public Row(String step, String slot, String type, boolean required,
                TenantSpec.FleetStep.Posture posture, Authority authority, String by,
                List<String> subProcessors) {
            this(step, slot, type, required, posture, authority, by, subProcessors, null);
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
            if (partOf != null) {
                canonical.append("\u0000part-of\u0000").append(partOf);
            }
            if (!subProcessors.isEmpty()) {
                canonical.append("\u0000sub\u0000")
                        .append(String.join(",", new java.util.TreeSet<>(subProcessors)));
            }
            if (purpose != null) {
                // Moved to another purpose, a row is not the one the tenant
                // agreed to: what it is done for is part of what it is.
                canonical.append("\u0000purpose\u0000").append(purpose.code());
            }
            return digestOver(canonical.toString());
        }
    }

    /** How a row names the service provider among those handling its data. */
    public static final String PROVIDER_PARTICIPANT = "provider";

    /**
     * What a tenant agreed to when it authorised a purpose: who handles the
     * data and which data.
     *
     * <p><b>Who and what, and nothing else.</b> A row of the purpose stands
     * while its participants and its type are among these, so a step renamed,
     * split or added under the purpose is covered, and a new partner or a new
     * kind of data is not until the tenant agrees again. That is the change a
     * processor tells a controller of, and the one a person decides
     * differently about; a digest moves for far less, and a register that
     * asked again for every move would be clicked through. The posture and
     * whether a row is required say what happens before agreement, not what
     * is agreed to, so they are no part of it.
     *
     * @param participants who handles the data, as {@link Row#participants()}
     *                     names them
     * @param types        what is opened, in the form the steps declared it
     */
    public record Agreement(java.util.SortedSet<String> participants,
            java.util.SortedSet<String> types) {

        public Agreement {
            participants = java.util.Collections.unmodifiableSortedSet(
                    new java.util.TreeSet<>(participants));
            types = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(types));
        }

        /** Everything these rows carry: what authorising their purpose now agrees to. */
        public static Agreement over(List<Row> rows) {
            java.util.SortedSet<String> who = new java.util.TreeSet<>();
            java.util.SortedSet<String> what = new java.util.TreeSet<>();
            for (Row row : rows) {
                who.addAll(row.participants());
                if (row.type() != null) {
                    what.add(row.type());
                }
            }
            return new Agreement(who, what);
        }

        /** Whether this row is within what was agreed. */
        public boolean covers(Row row) {
            return participants.containsAll(row.participants())
                    && (row.type() == null || types.contains(row.type()));
        }

        /** What these rows carry that was not agreed to: what a tenant is asked again about. */
        public Agreement beyond(List<Row> rows) {
            Agreement now = over(rows);
            java.util.SortedSet<String> who = new java.util.TreeSet<>(now.participants());
            who.removeAll(participants);
            java.util.SortedSet<String> what = new java.util.TreeSet<>(now.types());
            what.removeAll(types);
            return new Agreement(who, what);
        }

        public boolean isEmpty() {
            return participants.isEmpty() && types.isEmpty();
        }
    }

    /**
     * The digests of the rows that stand: each named itself, or serving a
     * purpose the tenant agreed to and within what it agreed to.
     */
    public static Set<String> standing(List<Row> rows, Set<String> digests,
            java.util.Map<String, Agreement> purposes) {
        Set<String> standing = new java.util.LinkedHashSet<>();
        for (Row row : rows) {
            Agreement agreed = row.purpose() == null ? null
                    : purposes.get(row.purpose().code());
            if (digests.contains(row.digest()) || (agreed != null && agreed.covers(row))) {
                standing.add(row.digest());
            }
        }
        return standing;
    }

    /** The rows of the register serving a purpose, in register order. */
    public static List<Row> serving(List<Row> rows, String purpose) {
        return rows.stream().filter(row -> row.purpose() != null
                && row.purpose().code().equals(purpose)).toList();
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
     * fulfils, and one for each step a holder of that work may start.
     *
     * <p><b>Not until approved, and never required.</b> Unlike a provider's
     * row, it stands only once the tenant has authorised it, because handing
     * work over is not what joining agreed to. Removing the line that
     * declared it declines it.
     *
     * <p><b>A row per part</b>, so a tenant widening what a holder may start
     * authorises the widening rather than finding it inside a row it already
     * authorised.
     */
    public static List<Row> handed(List<TenantSpec.Step> declared) {
        List<Row> rows = new ArrayList<>();
        for (TenantSpec.Step step : declared) {
            if (step.handsToProvider()) {
                java.util.Map.Entry<String, String> request =
                        step.slots().entrySet().iterator().next();
                rows.add(new Row(step.code(), request.getKey(), request.getValue(), false,
                        TenantSpec.FleetStep.Posture.NOT_UNTIL_APPROVED, Authority.TENANT,
                        null, List.of(), null, step.purposes().of(request.getKey())));
                for (String part : step.holdersStart()) {
                    // What the part serves is the part's own to say, since it
                    // is a step of its own the tenant may hand under other work.
                    TenantSpec.Purpose served = declared.stream()
                            .filter(one -> one.code().equals(part)).findFirst()
                            .map(one -> one.purposes().step()).orElse(null);
                    rows.add(new Row(part, null, null, false,
                            TenantSpec.FleetStep.Posture.NOT_UNTIL_APPROVED, Authority.TENANT,
                            null, List.of(), step.code(), served));
                }
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
                            List.copyOf(new java.util.TreeSet<>(step.delegatesTo())), null,
                            step.purposes().of(slot)));
                }
            });
        }
        return List.copyOf(rows);
    }
}
