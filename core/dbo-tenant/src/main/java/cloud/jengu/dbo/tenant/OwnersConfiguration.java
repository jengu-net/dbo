package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    /** What a file came to, on the way to a commit. */
    record Change(String path, String kind) {
    }

    /** What applying, or previewing, a ref came to. */
    record Applied(String ref, ConfigRepository.Commit commit, String by,
            ConfigApplication.Outcome outcome, List<Change> changes, boolean preview) {

        Applied {
            changes = List.copyOf(changes);
        }
    }

    private final String code;
    private final TenantSpec.Configuration configuration;
    private final ObjectStore store;
    private final Runs runs;
    private final ConfigApplication application;

    OwnersConfiguration(String code, TenantSpec.Configuration configuration, ObjectStore store,
            Runs runs, ConfigApplication application) {
        this.code = code;
        this.configuration = configuration;
        this.store = store;
        this.runs = runs;
        this.application = application;
    }

    /** The scope this source's sweep is keyed by, apart from any other source here. */
    static String scopeOf(String code) {
        return "git." + code;
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
        try (ConfigRepository repository = ConfigRepository.open(Path.of(configuration.git()))) {
            ConfigRepository.Commit commit = repository.resolve(asked);
            if (!preview && application.settledAt(scope, commit.id())) {
                return new Applied(asked, commit, by, ConfigApplication.Outcome.NOTHING,
                        List.of(), false);
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
                declared.add(new ConfigApplication.Declared(typeOf(bytes), file.path(), bytes));
            }
            for (String path : applied.keySet()) {
                if (!byPath.containsKey(path)) {
                    changes.add(new Change(path, "deleted"));
                }
            }
            if (preview) {
                return new Applied(asked, commit, by, ConfigApplication.Outcome.NOTHING,
                        changes, true);
            }

            // WHAT IS NO LONGER DECLARED: everything a deleted file applied,
            // and whatever a changed file applied before and names no longer.
            Map<Identifier, String> typeOf = new LinkedHashMap<>();
            Set<Identifier> gone = new LinkedHashSet<>();
            Map<String, Identifier> claims = new LinkedHashMap<>();
            for (ConfigApplication.Declared one : declared) {
                application.claimOf(one).ifPresent(claim -> claims.put(one.name(), claim));
            }
            for (ConfigFiles.Entry entry : applied.values()) {
                for (Identifier identity : entry.identities()) {
                    typeOf.put(identity, entry.type());
                    boolean deleted = !byPath.containsKey(entry.path());
                    boolean renamed = byPath.containsKey(entry.path())
                            && !entry.blob().equals(byPath.get(entry.path()).blob())
                            && !identity.equals(claims.get(entry.path()));
                    if (deleted || renamed) {
                        gone.add(identity);
                    }
                }
            }
            // Still declared by another file, after a move between files: the
            // record stays, and is that file's now.
            claims.values().forEach(gone::remove);

            Set<String> written = new LinkedHashSet<>();
            Set<Identifier> withdrawn = new LinkedHashSet<>();
            ConfigApplication.Applier applier = new ConfigApplication.Applier() {

                @Override
                public Verdict apply(ConfigApplication.Declared one) {
                    if (one.typeName().isEmpty()) {
                        throw new IllegalArgumentException(one.name() + " says no "
                                + "resourceType, so nothing says what it declares");
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
                List<Identifier> holds = new ArrayList<>();
                holds.add(claims.get(one.name()));
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
            return new Applied(asked, commit, by, outcome, changes, false);
        }
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
