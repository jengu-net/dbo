package cloud.jengu.dbo.core.api;

import java.util.Objects;

/**
 * What kind of data a type is: who may write it, whether it may change,
 * whether it is kept, and whether it may leave (§15).
 *
 * <p>Independent properties rather than a list of kinds. A list grows by one
 * every time somebody asks about a case it does not cover — audit, presence,
 * subscription cursors — and stops being able to place the next one. These
 * place any of them by answering a question each.
 *
 * <p>Declared per <b>type</b>, never per object. An object carrying its own
 * classification is a second truth, and one written with the wrong class gets
 * the wrong protection while appearing protected.
 */
public record Handling(Authority authority, Mutability mutability,
                       Durability durability, Travel travel, Provenance provenance) {

    /**
     * Whether a change to it has to belong to a piece of work (#82).
     *
     * <p>A change that belongs to nothing can still be seen — history has it,
     * audit names who — but nobody can say <b>what it was for</b>, and the
     * account of what happened has to be assembled afterwards from two places
     * that were never designed to agree.
     */
    public enum Provenance {

        /** Written on its own account. Configuration, credentials, bookkeeping. */
        ANY,

        /**
         * Written only inside a run, so the run names the versions it produced
         * and reading the runs in order reads the changes in order.
         *
         * <p>The rule is what turns work into the <b>manifest</b>: what must
         * travel to another appliance becomes derivable from runs rather than
         * computed by a second mechanism that has to agree with the first.
         */
        UNDER_A_RUN
    }

    /** Four properties, and a change that belongs to nothing in particular. */
    public Handling(Authority authority, Mutability mutability, Durability durability,
            Travel travel) {
        this(authority, mutability, durability, travel, Provenance.ANY);
    }

    /** The same type, with every change to it belonging to a run. */
    public Handling underARun() {
        return new Handling(authority, mutability, durability, travel, Provenance.UNDER_A_RUN);
    }

    /** Who may write it. */
    public enum Authority {
        /** Projected from the configuration repository by its sync lane. */
        CONFIG_LANE,
        /**
         * Published by an authority outside this system entirely — a national
         * terminology, a standards body's vocabulary — and carried here
         * through our own lane.
         *
         * <p>Distinct from {@link #CONFIG_LANE} because the lane says how it
         * ARRIVES and this says who it BELONGS to, and only the second decides
         * whether a defect in it can be fixed. Config we author reaches the
         * store the same way and is entirely ours to correct; a national
         * vocabulary is not, however it travelled (#100).
         */
        EXTERNAL_PUBLISHER,
        /** Published by another tenant — a zone's terminology, replicated here. */
        SOURCE_TENANT,
        /** Authored by the running platform: credentials, enrollments, lifecycle. */
        PLATFORM_RUNTIME,
        /** The tenant's own people and the modules acting for them. */
        TENANT_USERS,
        /** Nobody authors it; it is observed. A device is present or it is not. */
        OBSERVED
    }

    /** Whether, and how, it may change. */
    public enum Mutability {
        FULL,
        /**
         * Written once and never altered or removed — by anyone, including us.
         * Not a permission that could be granted in an emergency.
         */
        APPEND_ONLY,
        /** Writable only by its owning lane; refused from every other caller. */
        READ_ONLY_HERE,
        /** Overwritten wholesale; no history accrues. */
        REPLACE_IN_PLACE
    }

    /** Whether it is kept. */
    public enum Durability {
        /** Every version retained and hash-chained. */
        VERSIONED,
        /** Only the current value; no history. */
        CURRENT_ONLY,
        /** True for a moment, misleading afterwards. */
        EPHEMERAL
    }

    /** Where it may go. */
    public enum Travel {
        /** Into a backup, and into an export a customer leaves with. */
        BACKUP_AND_EXPORT,
        /** Into a backup only — never into a file handed to somebody else. */
        BACKUP_ONLY,
        /** As a read-only snapshot, for reference rather than ownership. */
        SNAPSHOT_ONLY,
        /** Nowhere. Restoring it would assert something that is not true. */
        NEVER
    }

    public Handling {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(mutability, "mutability");
        Objects.requireNonNull(durability, "durability");
        Objects.requireNonNull(travel, "travel");
        Objects.requireNonNull(provenance, "provenance");

        // Three combinations that are always mistakes, refused here rather than
        // discovered during a restore — which is where each of them surfaces.
        if (durability == Durability.EPHEMERAL && travel != Travel.NEVER) {
            throw new IllegalArgumentException(
                    "ephemeral data may not travel: restoring a momentary fact asserts it is "
                            + "still true — a device reported present a week after it was unplugged");
        }
        if (mutability == Mutability.APPEND_ONLY && durability == Durability.EPHEMERAL) {
            throw new IllegalArgumentException(
                    "an append-only record that expires is not append-only");
        }
        if (authority == Authority.OBSERVED && durability == Durability.VERSIONED) {
            throw new IllegalArgumentException(
                    "observed data must not accumulate versions: a hash-chained history of "
                            + "heartbeats buries the history that matters");
        }
    }

    /** Clinical and business records the tenant's own people create. */
    public static Handling operational() {
        return new Handling(Authority.TENANT_USERS, Mutability.FULL,
                Durability.VERSIONED, Travel.BACKUP_AND_EXPORT);
    }

    /** Configuration projected from git; the sync lane owns it. */
    public static Handling projectedConfig() {
        return new Handling(Authority.CONFIG_LANE, Mutability.FULL,
                Durability.VERSIONED, Travel.BACKUP_ONLY);
    }

    /**
     * Whether this store is the author of the content, or merely holds a copy
     * of somebody else's publication.
     *
     * <p>Validation is a gate on <b>authorship</b>. Refusing a resource this
     * store's own callers author is how bad data is prevented: there is a
     * writer here who can fix it. Refusing another authority's publication
     * prevents nothing — it cannot make their publication correct, and it
     * cannot be fixed here either, because {@link Mutability#READ_ONLY_HERE}
     * is exactly the statement that nobody here may touch it. The only thing
     * such a refusal changes is that the vocabulary is absent rather than
     * imperfect, and a jurisdiction's clinicians lose their diagnosis coding
     * over a property URI that is not absolute (#100).
     *
     * <p>Deliberately narrower than "not authored by this tenant's users":
     * configuration projected from git is authored by us through a lane, and
     * keeps every bit of today's strictness.
     */
    public boolean authoredElsewhere() {
        return authority == Authority.SOURCE_TENANT
                || authority == Authority.EXTERNAL_PUBLISHER;
    }

    /** Published by another tenant: read here, never written here. */
    public static Handling replicated() {
        return new Handling(Authority.SOURCE_TENANT, Mutability.READ_ONLY_HERE,
                Durability.VERSIONED, Travel.SNAPSHOT_ONLY);
    }

    /**
     * Another authority's publication, carried here by our own lane: a
     * national terminology, a standards body's vocabulary.
     *
     * <p>Writable the way projected configuration is — the lane has to be able
     * to put it there — but not OURS, which is the whole difference. A defect
     * in it cannot be fixed here and cannot be fixed at the source by us, so
     * validation records rather than refuses (#100). Correcting it locally
     * would be worse than holding it as published: our copy of a national
     * vocabulary would then differ from everyone else's.
     *
     * <p>Backup rather than snapshot: it is re-derivable from the publication
     * it was carried from, exactly like the configuration beside it.
     */
    public static Handling mirrored() {
        return new Handling(Authority.EXTERNAL_PUBLISHER, Mutability.FULL,
                Durability.VERSIONED, Travel.BACKUP_ONLY);
    }

    /**
     * Authored by the platform and regenerable from nowhere — credentials,
     * enrollments, lifecycle. A backup without it cannot authenticate its own
     * tenants; an export containing it hands somebody our keys.
     */
    public static Handling storeAuthored() {
        return new Handling(Authority.PLATFORM_RUNTIME, Mutability.FULL,
                Durability.VERSIONED, Travel.BACKUP_ONLY);
    }

    /** What the system recorded about who did what. Never altered. */
    public static Handling audit() {
        return new Handling(Authority.PLATFORM_RUNTIME, Mutability.APPEND_ONLY,
                Durability.VERSIONED, Travel.BACKUP_ONLY);
    }

    /** Presence, liveness, delivery progress: true now, misleading later. */
    public static Handling ephemeral() {
        return new Handling(Authority.OBSERVED, Mutability.REPLACE_IN_PLACE,
                Durability.EPHEMERAL, Travel.NEVER);
    }

    /** Whether a write from outside the owning lane must be refused. */
    public boolean isWritableBy(Authority caller) {
        return authority == caller;
    }

    /** Why a write would be refused by handling alone. */
    public enum WriteRefusal {
        /** It exists already and may never be altered or removed. */
        APPEND_ONLY,
        /** Its owning lane may write it and this caller is not that lane. */
        READ_ONLY_HERE
    }

    /**
     * Whether handling alone refuses this write, and why — the ONE rule, asked
     * by the engine that enforces it and by the CapabilityStatement that
     * describes it.
     *
     * <p>They had drifted, and in the direction that matters: the statement
     * asked {@code isWritableBy(TENANT_USERS)} and omitted {@code create} for
     * every type a lane owns, while the engine happily accepted those creates
     * — so a store advertised a type as read-only and then wrote it (#104).
     *
     * <p>The engine's rule is the deliberate one. {@code replicated()} chose
     * {@link Mutability#READ_ONLY_HERE} and {@code projectedConfig()} chose
     * {@link Mutability#FULL}, and that contrast is the design: mutability
     * answers whether anyone here may write it, authority answers whose it is.
     * Only the first is a permission. Enforcing ownership instead would erase
     * a distinction the classifications make on purpose — and would refuse the
     * lane loads that put configuration and mirrored terminology there at all.
     *
     * @param caller   the authority the write is made under
     * @param creating true for a create, false for an update or a delete;
     *                 append-only permits the first and refuses the second
     */
    public WriteRefusal refusalFor(Authority caller, boolean creating) {
        if (mutability == Mutability.APPEND_ONLY && !creating) {
            return WriteRefusal.APPEND_ONLY;
        }
        if (mutability == Mutability.READ_ONLY_HERE && !isWritableBy(caller)) {
            return WriteRefusal.READ_ONLY_HERE;
        }
        return null;
    }

    /** Whether a change to this has to belong to a run. */
    public boolean requiresARun() {
        return provenance == Provenance.UNDER_A_RUN;
    }

    /** Whether this may be placed in an archive of the given kind. */
    public boolean travelsInBackup() {
        return travel != Travel.NEVER;
    }

    public boolean travelsInPortableExport() {
        return travel == Travel.BACKUP_AND_EXPORT || travel == Travel.SNAPSHOT_ONLY;
    }
}
