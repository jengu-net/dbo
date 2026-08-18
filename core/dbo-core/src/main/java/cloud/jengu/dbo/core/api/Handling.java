package cloud.jengu.dbo.core.api;

import java.util.Objects;

/**
 * What kind of data a type is: who may write it, whether it may change,
 * whether it is kept, and whether it may leave (§15).
 *
 * <p>Four independent properties rather than a list of kinds. A list grows by
 * one every time somebody asks about a case it does not cover — audit,
 * presence, subscription cursors — and stops being able to place the next one.
 * These four place any of them by answering four questions.
 *
 * <p>Declared per <b>type</b>, never per object. An object carrying its own
 * classification is a second truth, and one written with the wrong class gets
 * the wrong protection while appearing protected.
 */
public record Handling(Authority authority, Mutability mutability,
                       Durability durability, Travel travel) {

    /** Who may write it. */
    public enum Authority {
        /** Projected from the configuration repository by its sync lane. */
        CONFIG_LANE,
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

    /** Published by another tenant: read here, never written here. */
    public static Handling replicated() {
        return new Handling(Authority.SOURCE_TENANT, Mutability.READ_ONLY_HERE,
                Durability.VERSIONED, Travel.SNAPSHOT_ONLY);
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

    /** Whether this may be placed in an archive of the given kind. */
    public boolean travelsInBackup() {
        return travel != Travel.NEVER;
    }

    public boolean travelsInPortableExport() {
        return travel == Travel.BACKUP_AND_EXPORT || travel == Travel.SNAPSHOT_ONLY;
    }
}
