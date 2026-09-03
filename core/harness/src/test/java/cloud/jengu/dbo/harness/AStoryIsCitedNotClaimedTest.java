package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promise.Promise;
import cloud.jengu.dbo.promise.Proofs;
import cloud.jengu.dbo.promise.Registry;
import cloud.jengu.dbo.promise.Story;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A story is a constant beside the promises, and claims no evidence of its
 * own.
 *
 * <p>Two of the rules hold by construction and are stated here rather than
 * tested: a story cites promises as constants, so one that does not exist
 * does not compile; and its joins are projected from the declaration, so a
 * leg nothing promises cannot be written. What can be shown is the rest —
 * that every story declared has a scene, that a story with no scene or no
 * joins is refused, and that a story resting only on planned promises reads
 * unproven rather than listing legs a reader would take as evidence.
 */
class AStoryIsCitedNotClaimedTest {

    private static Registry.Model model() {
        ClassLoader loader = AStoryIsCitedNotClaimedTest.class.getClassLoader();
        return Registry.load(loader).model(Proofs.load(loader));
    }

    private static final Path STORIES = Path.of("../..",
            "docs/arc42-003-context/user-stories").toAbsolutePath().normalize();

    @Test
    @DisplayName("every story the catalogue declares has its scene at the path its code names, "
            + "and declares at least one promise")
    @Proving(DboPromises.PRM_A_STORY_IS_CITED_NOT_CLAIMED)
    void everyStoryIsAConstantAndAPage() {
        List<Story> stories = PromiseProjection.stories(model());
        assertEquals(DboStories.values().length, stories.size(),
                "the registry sees every story constant: " + stories);
        for (Story story : stories) {
            Path page = PromiseProjection.storyFile(STORIES, model(), story);
            assertTrue(Files.exists(page), page.toString());
            assertFalse(story.promises().isEmpty(), story.code() + " declares no promise");
        }
    }

    @Test
    @DisplayName("a story constant with no scene, and a story with no joins, are refused rather "
            + "than projected into nothing")
    @Proving(DboPromises.PRM_A_STORY_IS_CITED_NOT_CLAIMED)
    void aStoryWithoutAPageOrWithoutJoinsIsRefused() {
        Story unwritten = new Story() {
            @Override
            public String code() {
                return "US-DBO-NEVER-WRITTEN";
            }

            @Override
            public String title() {
                return "a story somebody declared and never told";
            }

            @Override
            public List<Promise> promises() {
                return List.of(DboPromises.POL_AUDIT_AS_RECORDS);
            }
        };
        IllegalStateException noPage = assertThrows(IllegalStateException.class,
                () -> PromiseProjection.storyFile(STORIES, model(), unwritten));
        assertTrue(noPage.getMessage().contains("US-DBO-NEVER-WRITTEN"), noPage.getMessage());

        Story empty = new Story() {
            @Override
            public String code() {
                return "US-DBO-ONLY-PROSE";
            }

            @Override
            public String title() {
                return "a scene resting on nothing";
            }

            @Override
            public List<Promise> promises() {
                return List.of();
            }
        };
        IllegalStateException noJoins = assertThrows(IllegalStateException.class,
                () -> PromiseProjection.storyBlock(model(), empty));
        assertTrue(noJoins.getMessage().contains("declares no promise"), noJoins.getMessage());
    }

    @Test
    @DisplayName("a story whose every leg is planned reads unproven; one with a proven leg "
            + "shows its coverage")
    @Proving(DboPromises.PRM_A_STORY_IS_CITED_NOT_CLAIMED)
    void aStoryOnPlannedLegsReadsUnproven() {
        Registry.Model model = model();
        Promise planned = model.promises().stream()
                .filter(p -> model.statusOf(p) == cloud.jengu.dbo.promise.PromiseStatus.PLANNED)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no planned promise left to rest a story on — rewrite this test with a gap"));
        Story hopeful = new Story() {
            @Override
            public String code() {
                return "US-DBO-ALL-HOPE";
            }

            @Override
            public String title() {
                return "a scene told ahead of the store";
            }

            @Override
            public List<Promise> promises() {
                return List.of(planned);
            }
        };
        String block = PromiseProjection.storyBlock(model, hopeful);
        assertTrue(block.contains("**Unproven.**"), block);
        assertTrue(block.contains(model.codeOf(planned)) && block.contains("PLANNED"), block);

        String real = PromiseProjection.storyBlock(model, DboStories.VENDOR_CHANGE);
        assertFalse(real.contains("**Unproven.**"), real);
        assertTrue(real.contains("Coverage:"), real);
    }
}
