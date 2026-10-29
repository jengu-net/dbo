package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tenant owner's configuration, applied from its repository at a commit.
 *
 * <p><b>Reconciled, never replayed.</b> What the commit declares is compared
 * with what the store recorded each file applied, file by file: a file whose
 * blob is the one applied is not read at all, a changed or new one is read and
 * applied, and what a file stopped declaring — because the file is gone, or
 * because it now names something else — is withdrawn, and nothing anybody
 * else declared. Applying an older commit is the same comparison, so a
 * rollback is an apply, and it works whatever happened to the history between.
 *
 * <p>Every pass is the sweep run of its own scope, so the repository's commit
 * is what the run is correlated with and a second apply of the same commit
 * reads nothing. Each apply that did something also records an act under that
 * run naming the ref asked for, the commit, its author and message, and who
 * asked — the author is whatever the committer typed, so who asked is said
 * beside it.
 */
final class OwnersConfiguration {

    /** The step an apply is recorded as, under the configuration's sweep. */
    static final String COMMIT = "commit";

    /** What a file of secrets declares, in the account of what each file applied. */
    static final String SECRETS = "Secrets";

    /** A file of secrets, encrypted as SOPS writes them. */
    static final String SECRETS_SUFFIX = ".sops.json";

    /** Where the tenant keeps the secrets its owner gives it. */
    interface Secrets {

        /**
         * A file's secrets, opened through the tenant's own key.
         *
         * @throws IllegalArgumentException when it cannot be, naming why
         */
        Map<String, String> open(SopsFile file);

        void keep(String name, String value);

        void forget(String name);
    }

    /** What a file came to, on the way to a commit. */
    record Change(String path, String kind) {
    }

    /**
     * What applying, or previewing, a ref came to.
     *
     * @param othersMayChange the types of what it applies that writers other
     *        than the configuration may change — users, steps, a site's runs.
     *        Said because the configuration being the source of truth for a
     *        type is the type's handling to declare, and a type declared
     *        {@code operational} is everybody's: a reader finds that out here,
     *        before somebody else's write does it for them.
     */
    record Applied(String ref, ConfigRepository.Commit commit, String by,
            ConfigApplication.Outcome outcome, List<Change> changes, boolean preview,
            List<String> othersMayChange) {

        Applied {
            changes = List.copyOf(changes);
            othersMayChange = List.copyOf(othersMayChange);
        }
    }

    private final String code;
    private final TenantSpec.Configuration configuration;
    private final java.util.function.Supplier<ConfigRepository> repository;
    private final ObjectStore store;
    private final Runs runs;
    private final ConfigApplication application;
    private final Secrets secrets;

    /**
     * @param repository opens the tenant's repository, once for each apply
     */
    OwnersConfiguration(String code, TenantSpec.Configuration configuration,
            java.util.function.Supplier<ConfigRepository> repository, ObjectStore store,
            Runs runs, ConfigApplication application, Secrets secrets) {
        this.code = code;
        this.configuration = configuration;
        this.repository = repository;
        this.store = store;
        this.runs = runs;
        this.application = application;
        this.secrets = secrets;
    }

    /** The scope this source's sweep is keyed by, apart from any other source here. */
    static String scopeOf(String code) {
        return "git." + code;
    }

    /**
     * What a place let go of: by the account of what its configuration
     * applied, by the type's own word that only the configuration writes it,
     * and what the account names that no lookup found.
     */
    record Released(int byAccount, int byHandling, int unfound) {
    }

    /**
     * Withdraws, at a place, what its owner's configuration applied there
     * before the place left that configuration to its origin, wherever it
     * stands in front of a version the origin sends.
     *
     * <p>Such a record is local, with no origin, so the stream reads every
     * version the origin publishes of it as a local override and parks it,
     * for good: nobody overrode anything, and nothing else would remove it.
     * Only what the configuration wrote is withdrawn, so a record somebody at
     * the place wrote keeps shadowing.
     *
     * <p>What the configuration wrote is read first from its account. An
     * entry is forgotten once each of its records is released or none is
     * left standing; one whose lookup finds nothing while a record of its
     * type still shadows is kept and counted, since that record may be the
     * one it applied, and forgetting the entry would be forgetting the only
     * account of it. Then, account or not, a shadowing record of a type only
     * the configuration lane may write is the configuration's: nobody at the
     * place could have written it.
     *
     * @param shadowing the local records holding an upstream version back,
     *        by id, with their type
     */
    static Released releasedAtAPlace(String code, ObjectStore store,
            Map<String, String> shadowing) {
        ConfigFiles ledger = new ConfigFiles(store, scopeOf(code));
        Set<String> released = new LinkedHashSet<>();
        List<ConfigFiles.Entry> unfound = new ArrayList<>();
        for (ConfigFiles.Entry entry : ledger.applied().values()) {
            if (SECRETS.equals(entry.type())) {
                continue;
            }
            boolean anyLeft = false;
            boolean anyUnfound = false;
            for (Identifier identity : entry.identities()) {
                List<StoredObject> found = store.getByIdentifier(entry.type(),
                        List.of(identity));
                anyUnfound |= found.isEmpty();
                for (StoredObject record : found) {
                    if (shadowing.containsKey(record.id())) {
                        store.delete(entry.type(), record.id(), record.versionId(),
                                cloud.jengu.dbo.core.api.Handling.Authority.CONFIG_LANE);
                        released.add(record.id());
                    } else {
                        anyLeft = true;
                    }
                }
            }
            // Kept while it still names a record here, so one the origin
            // publishes later is released on the place's next start.
            if (anyUnfound) {
                unfound.add(entry);
            } else if (!anyLeft) {
                ledger.forget(entry);
            }
        }
        int byAccount = released.size();
        for (Map.Entry<String, String> shadow : shadowing.entrySet()) {
            if (released.contains(shadow.getKey())
                    || !writtenOnlyByTheConfiguration(store, shadow.getValue())) {
                continue;
            }
            store.get(shadow.getValue(), shadow.getKey())
                    .filter(record -> record.origin() == null)
                    .ifPresent(record -> {
                        store.delete(shadow.getValue(), record.id(), record.versionId(),
                                cloud.jengu.dbo.core.api.Handling.Authority.CONFIG_LANE);
                        released.add(record.id());
                    });
        }
        Set<String> stillShadowing = new LinkedHashSet<>();
        shadowing.forEach((id, type) -> {
            if (!released.contains(id)) {
                stillShadowing.add(type);
            }
        });
        int stillUnfound = 0;
        for (ConfigFiles.Entry entry : unfound) {
            if (stillShadowing.contains(entry.type())) {
                stillUnfound++;
            } else {
                ledger.forget(entry);
            }
        }
        return new Released(byAccount, released.size() - byAccount, stillUnfound);
    }

    private static boolean writtenOnlyByTheConfiguration(ObjectStore store, String type) {
        try {
            cloud.jengu.dbo.core.api.Handling handling = store.registrationOf(type).handling();
            return handling.authority() == cloud.jengu.dbo.core.api.Handling.Authority.CONFIG_LANE
                    && handling.mutability()
                            == cloud.jengu.dbo.core.api.Handling.Mutability.READ_ONLY_HERE;
        } catch (cloud.jengu.dbo.core.api.UnknownTypeException notHere) {
            return false;
        }
    }

    /**
     * Applies a ref, or says what applying it would do.
     *
     * @param ref     a branch, tag or commit, or null for the declared one
     * @param by      who asked: a client, or the deployment bringing the
     *                tenant up
     * @param preview whether to stop at saying what would change
     */
    Applied apply(String ref, String by, boolean preview) {
        String asked = ref == null ? configuration.ref() : ref;
        String scope = scopeOf(code);
        try (ConfigRepository repository = this.repository.get()) {
            ConfigRepository.Commit commit = repository.resolve(asked);
            if (!preview && application.settledAt(scope, commit.id())) {
                return new Applied(asked, commit, by, ConfigApplication.Outcome.NOTHING,
                        List.of(), false, List.of());
            }
            ConfigFiles ledger = new ConfigFiles(store, scope);
            Map<String, ConfigFiles.Entry> applied = ledger.applied();
            List<ConfigRepository.File> files = repository.files(commit);

            List<Change> changes = new ArrayList<>();
            List<ConfigApplication.Declared> declared = new ArrayList<>();
            Map<String, ConfigRepository.File> byPath = new LinkedHashMap<>();
            for (ConfigRepository.File file : files) {
                byPath.put(file.path(), file);
                ConfigFiles.Entry was = applied.get(file.path());
                if (was != null && was.blob().equals(file.blob())) {
                    continue;
                }
                changes.add(new Change(file.path(), was == null ? "added" : "modified"));
                byte[] bytes = repository.read(file);
                declared.add(new ConfigApplication.Declared(
                        file.path().endsWith(SECRETS_SUFFIX) ? SECRETS : typeOf(bytes),
                        file.path(), bytes));
            }
            for (String path : applied.keySet()) {
                if (!byPath.containsKey(path)) {
                    changes.add(new Change(path, "deleted"));
                }
            }
            if (preview) {
                return new Applied(asked, commit, by, ConfigApplication.Outcome.NOTHING,
                        changes, true, othersMayChange(declared));
            }

            // WHAT IS NO LONGER DECLARED: everything a deleted file applied,
            // and whatever a changed file applied before and names no longer.
            Map<Identifier, String> typeOf = new LinkedHashMap<>();
            Set<Identifier> gone = new LinkedHashSet<>();
            Map<String, List<Identifier>> claims = new LinkedHashMap<>();
            for (ConfigApplication.Declared one : declared) {
                List<Identifier> named = claimsOf(one);
                if (!named.isEmpty()) {
                    claims.put(one.name(), named);
                }
            }
            for (ConfigFiles.Entry entry : applied.values()) {
                for (Identifier identity : entry.identities()) {
                    typeOf.put(identity, entry.type());
                    boolean deleted = !byPath.containsKey(entry.path());
                    boolean renamed = byPath.containsKey(entry.path())
                            && !entry.blob().equals(byPath.get(entry.path()).blob())
                            && !claims.getOrDefault(entry.path(), List.of()).contains(identity);
                    if (deleted || renamed) {
                        gone.add(identity);
                    }
                }
            }
            // Still declared by another file, after a move between files: the
            // record stays, and is that file's now.
            claims.values().forEach(gone::removeAll);
            // A secret's name is the tenant's, whichever file holds it: two
            // files naming one are refused, the later in the commit.
            Map<String, String> secretHeldBy = new LinkedHashMap<>();
            for (ConfigFiles.Entry entry : applied.values()) {
                if (SECRETS.equals(entry.type()) && byPath.containsKey(entry.path())
                        && !claims.containsKey(entry.path())) {
                    entry.identities().forEach(name -> secretHeldBy.put(name.value(),
                            entry.path()));
                }
            }

            Set<String> written = new LinkedHashSet<>();
            Set<Identifier> withdrawn = new LinkedHashSet<>();
            ConfigApplication.Applier applier = new ConfigApplication.Applier() {

                @Override
                public Verdict apply(ConfigApplication.Declared one) {
                    if (one.typeName().isEmpty()) {
                        throw new IllegalArgumentException(one.name() + " says no "
                                + "resourceType, so nothing says what it declares");
                    }
                    if (SECRETS.equals(one.typeName())) {
                        SopsFile file = SopsFile.parse(one.name(), one.payload());
                        for (Identifier name : claims.getOrDefault(one.name(), List.of())) {
                            String holder = secretHeldBy.putIfAbsent(name.value(), one.name());
                            if (holder != null && !holder.equals(one.name())) {
                                throw new IllegalArgumentException(one.name() + " names the "
                                        + "secret '" + name.value() + "', which " + holder
                                        + " already does");
                            }
                        }
                        Map<String, String> opened = secrets.open(file);
                        opened.forEach(secrets::keep);
                        written.add(one.name());
                        return Verdict.APPLIED;
                    }
                    // A record nothing names cannot be found again to withdraw,
                    // so a source that keeps an account of what it applied
                    // cannot declare one.
                    if (!claims.containsKey(one.name())) {
                        throw new IllegalArgumentException(one.name() + " declares a "
                                + one.typeName() + " with nothing that names it — a url or "
                                + "an identifier its type is keyed by — so it could never "
                                + "be withdrawn");
                    }
                    Verdict verdict = application.intoTheStore(one);
                    written.add(one.name());
                    return verdict;
                }

                @Override
                public void withdraw(String scope, Identifier identity) {
                    if (SECRET.equals(identity.system())) {
                        secrets.forget(identity.value());
                        withdrawn.add(identity);
                        return;
                    }
                    String type = typeOf.get(identity);
                    for (StoredObject record : store.getByIdentifier(type, List.of(identity))) {
                        store.delete(type, record.id(), record.versionId(),
                                cloud.jengu.dbo.core.api.Handling.Authority.CONFIG_LANE);
                    }
                    withdrawn.add(identity);
                }
            };
            ConfigApplication.Outcome outcome = application.reconcile(scope, commit.id(),
                    declared, List.copyOf(gone), applier);

            // THE ACCOUNT, moved only as far as what landed. A file that was
            // refused keeps the entry it had, so the next apply reads it again;
            // an identity that was not withdrawn stays on the file that held
            // it, so it is withdrawn once whatever blocked it is fixed.
            for (ConfigApplication.Declared one : declared) {
                if (!written.contains(one.name())) {
                    continue;
                }
                List<Identifier> holds = new ArrayList<>(claims.get(one.name()));
                ConfigFiles.Entry was = applied.get(one.name());
                if (was != null) {
                    for (Identifier identity : was.identities()) {
                        if (gone.contains(identity) && !withdrawn.contains(identity)
                                && !holds.contains(identity)) {
                            holds.add(identity);
                        }
                    }
                }
                ledger.record(one.name(), byPath.get(one.name()).blob(), one.typeName(), holds);
            }
            for (ConfigFiles.Entry entry : applied.values()) {
                if (byPath.containsKey(entry.path())) {
                    continue;
                }
                if (withdrawn.containsAll(entry.identities())
                        || entry.identities().stream().noneMatch(gone::contains)) {
                    ledger.forget(entry);
                }
            }
            runs.byKey(ConfigApplication.PROCESS + "/" + ConfigApplication.STEP + "/" + scope)
                    .ifPresent(sweep -> recordAct(sweep, asked, commit, by));
            return new Applied(asked, commit, by, outcome, changes, false,
                    othersMayChange(declared));
        }
    }

    /** The declared types whose handling lets a writer other than the configuration change them. */
    private List<String> othersMayChange(List<ConfigApplication.Declared> declared) {
        java.util.TreeSet<String> types = new java.util.TreeSet<>();
        for (ConfigApplication.Declared one : declared) {
            cloud.jengu.dbo.core.api.TypeRegistration registration;
            try {
                registration = store.registrationOf(one.typeName());
            } catch (cloud.jengu.dbo.core.api.UnknownTypeException notStored) {
                // A file of secrets, or a type the pass will card: nothing of
                // it is a record anybody else could write.
                continue;
            }
            if (registration != null && registration.handling().mutability()
                    != cloud.jengu.dbo.core.api.Handling.Mutability.READ_ONLY_HERE) {
                types.add(one.typeName());
            }
        }
        return List.copyOf(types);
    }

    /**
     * Which commit was applied, at whose asking and why, as an act under the
     * sweep: one per apply, so a rollback and the apply it undid are both
     * there to read.
     */
    private void recordAct(Run sweep, String asked, ConfigRepository.Commit commit, String by) {
        String subject = commit.message().lines().findFirst().orElse("");
        runs.act(sweep, ConfigApplication.PROCESS, COMMIT,
                scopeOf(code) + "/" + cloud.jengu.dbo.core.UuidV7.newId(), by,
                "applied " + asked + " at " + commit.id() + ", by " + commit.author() + ": "
                        + subject);
    }

    /** How a secret is named in the account of what each file applied. */
    static final String SECRET = cloud.jengu.dbo.auth.IdentityModel.SECRET_SYSTEM;

    /**
     * What a file declares, by what it would be found by again: a record's
     * identity, or the names a file of secrets holds — readable before it is
     * opened, because SOPS encrypts values and not names.
     */
    private List<Identifier> claimsOf(ConfigApplication.Declared one) {
        if (SECRETS.equals(one.typeName())) {
            try {
                return SopsFile.parse(one.name(), one.payload()).names().stream()
                        .map(name -> new Identifier(SECRET, name)).toList();
            } catch (IllegalArgumentException unreadable) {
                return List.of();
            }
        }
        return application.claimOf(one).map(List::of).orElse(List.of());
    }

    /** The type a file declares, as it says itself, or empty where it says none. */
    private static String typeOf(byte[] bytes) {
        try {
            if (cloud.jengu.dbo.core.wire.RecordWire.read(new String(bytes,
                    StandardCharsets.UTF_8)) instanceof Map<?, ?> json
                    && json.get("resourceType") instanceof String type) {
                return type;
            }
        } catch (RuntimeException unreadable) {
            // Said by the applier, as a card naming the file.
        }
        return "";
    }
}
