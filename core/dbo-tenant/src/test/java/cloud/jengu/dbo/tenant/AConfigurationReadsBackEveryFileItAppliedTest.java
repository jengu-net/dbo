package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An owner's configuration of more files than one page of a selection.
 *
 * <p>What a commit changes is decided against the account of what each file
 * applied, and that account is read back as a question to the store. Read
 * back as one page, a configuration of more files than the page was told that
 * files applied days ago were new, never withdrew one removed past the page,
 * and released at a place only the first page of what it had applied there.
 */
class AConfigurationReadsBackEveryFileItAppliedTest {

    private static final String CODE = "clinic";
    private static final String ORG = "urn:example:org";
    /** More than a page of a selection, which is a hundred unless asked otherwise. */
    private static final int FILES = 150;

    private final ConfigurationInMemory tenant = new ConfigurationInMemory();
    private final ObjectStore store = tenant.store();
    /** Each ward's record, by id, as the stream would name it shadowing. */
    private final Map<String, String> shadowing = new java.util.LinkedHashMap<>();
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
                shadowing.put(tenant.holds("Organization", ward), "Organization");
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
        tenant.handles("Organization", cloud.jengu.dbo.core.api.Handling.operational());
        assertEquals(FILES, OwnersConfiguration.releasedAtAPlace(CODE, store, shadowing)
                .byAccount());
        assertEquals(0, tenant.count("Organization"),
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
}
