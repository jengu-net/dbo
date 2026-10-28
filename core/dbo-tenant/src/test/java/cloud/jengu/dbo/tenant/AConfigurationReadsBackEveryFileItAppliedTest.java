package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An owner's configuration of more files than one page of a selection.
 *
 * <p>What a commit changes is decided against the account of what each file
 * applied, and that account is read back as a question to the store. Read
 * back as one page, a configuration of more files than the page was told that
 * files applied days ago were new, never withdrew one removed past the page,
 * and released at a place only the first page of what it had applied there.
 *
 * <p>The store here stops a selection at its limit and continues a page after
 * an id, as the store's do; nothing else of a database is in question.
 */
class AConfigurationReadsBackEveryFileItAppliedTest {

    private static final String CODE = "clinic";
    private static final String ORG = "urn:example:org";
    /** More than a page of a selection, which is a hundred unless asked otherwise. */
    private static final int FILES = 150;

    private final Map<String, StoredObject> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byIdentifier = new ConcurrentHashMap<>();
    private final ObjectStore store = store();
    private Path repository;
    private Git git;

    @BeforeEach
    void aConfigurationAppliedFileByFile() throws Exception {
        repository = Files.createTempDirectory("dbo-config-whole");
        git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call();
        for (int ward = 1; ward <= FILES; ward++) {
            Files.writeString(repository.resolve("ward-" + ward + ".json"), """
                    {"resourceType":"Organization","identifier":[{"system":"%s","value":"ward-%d"}]}"""
                    .formatted(ORG, ward));
        }
        commit("Every ward");
        // What applying that commit left: each ward's record, and the account
        // of which file applied it.
        ConfigFiles ledger = new ConfigFiles(store, OwnersConfiguration.scopeOf(CODE));
        try (ConfigRepository read = ConfigRepository.open(repository)) {
            for (ConfigRepository.File file : read.files(read.resolve("main"))) {
                Identifier ward = new Identifier(ORG, file.path().replace(".json", ""));
                ledger.record(file.path(), file.blob(), "Organization", List.of(ward));
                store.putConditional(IdentityRef.identifier(ORG, ward.value()),
                        PutRequest.create("Organization", new byte[0]));
            }
        }
    }

    @AfterEach
    void down() {
        git.close();
    }

    @Test
    @Proving(DboPromises.TEN_AN_OWNERS_CONFIGURATION_IS_APPLIED_FROM_ITS_REPOSITORY)
    @DisplayName("the commit applied previews as no change, however many files it holds")
    void theCommitAppliedChangesNothing() {
        assertEquals(List.of(), configuration().apply(null, "owner", true).changes(),
                "files applied before were previewed as new");
    }

    @Test
    @Proving(DboPromises.TEN_AN_OWNERS_CONFIGURATION_IS_APPLIED_FROM_ITS_REPOSITORY)
    @DisplayName("a file removed past the first page of the account is withdrawn")
    void aFileRemovedPastThePageIsWithdrawn() throws Exception {
        Files.delete(repository.resolve("ward-" + FILES + ".json"));
        git.rm().addFilepattern("ward-" + FILES + ".json").call();
        commit("Close the last ward");

        assertEquals(List.of(new OwnersConfiguration.Change("ward-" + FILES + ".json", "deleted")),
                configuration().apply(null, "owner", true).changes());
    }

    @Test
    @Proving(DboPromises.SYNC_A_PLACE_LETS_GO_OF_WHAT_ITS_OLD_CONFIGURATION_APPLIED)
    @DisplayName("a place releases every record its old configuration applied, not the first page")
    void aPlaceReleasesEveryRecord() {
        assertEquals(FILES, OwnersConfiguration.releasedAtAPlace(CODE, store, id -> true));
        assertEquals(0, byId.values().stream()
                .filter(stored -> stored.typeName().equals("Organization")).count(),
                "records the configuration applied still stand in front of the origin's");
        assertEquals(Map.of(), new ConfigFiles(store, OwnersConfiguration.scopeOf(CODE)).applied(),
                "the account still names records that are gone");
    }

    private OwnersConfiguration configuration() {
        return new OwnersConfiguration(CODE, new TenantSpec.Configuration(repository.toString(),
                "main"), store, null, null, null);
    }

    private void commit(String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("Ada", "ada@example.org")
                .setCommitter("Ada", "ada@example.org").call();
    }

    private ObjectStore store() {
        return (ObjectStore) Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "putConditional" -> put((IdentityRef.ByIdentifier) args[0],
                                    (PutRequest) args[1]);
                            case "getByIdentifier" -> ((List<?>) args[1]).stream()
                                    .map(one -> (Identifier) one)
                                    .map(one -> byIdentifier.get(args[0] + "|" + one.system()
                                            + "|" + one.value()))
                                    .filter(java.util.Objects::nonNull).map(byId::get).toList();
                            case "delete" -> {
                                StoredObject gone = byId.remove((String) args[1]);
                                byIdentifier.values().remove(gone.id());
                                yield null;
                            }
                            case "select" -> matching((Criteria) args[0]).stream()
                                    .limit(((Criteria) args[0]).limitValue()).toList();
                            case "page" -> page((Criteria) args[0], (String) args[1]);
                            case "toString" -> "configuration in memory";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    private synchronized PutResult put(IdentityRef.ByIdentifier identity, PutRequest request) {
        String named = request.typeName() + "|" + identity.identifier().system() + "|"
                + identity.identifier().value();
        String id = byIdentifier.computeIfAbsent(named,
                ignored -> cloud.jengu.dbo.core.UuidV7.newId());
        StoredObject was = byId.get(id);
        long version = was == null ? 1 : was.versionId() + 1;
        byId.put(id, new StoredObject(id, request.typeName(), version, Instant.now(),
                request.payload(), false, null));
        return new PutResult(id, version, was == null);
    }

    private FeedChunk<StoredObject> page(Criteria criteria, String after) {
        List<StoredObject> rest = matching(criteria).stream()
                .filter(stored -> after == null || stored.id().compareTo(after) > 0)
                .toList();
        List<StoredObject> page = rest.subList(0, Math.min(rest.size(), criteria.limitValue()));
        boolean drained = page.size() == rest.size();
        return new FeedChunk<>(page, drained ? null : page.get(page.size() - 1).id(), drained);
    }

    private List<StoredObject> matching(Criteria criteria) {
        List<StoredObject> found = new ArrayList<>();
        for (StoredObject stored : byId.values()) {
            if (!stored.typeName().equals(criteria.typeName())) {
                continue;
            }
            Map<String, List<EnvelopeValue>> paths = ConfigFiles.registrations().get(0)
                    .extractor().extract(stored.typeName(), stored.payload()).paths();
            if (criteria.equalsPredicates().stream().allMatch(eq ->
                    paths.getOrDefault(eq.path(), List.of()).contains(eq.value()))) {
                found.add(stored);
            }
        }
        found.sort(Comparator.comparing(StoredObject::id));
        return found;
    }
}
