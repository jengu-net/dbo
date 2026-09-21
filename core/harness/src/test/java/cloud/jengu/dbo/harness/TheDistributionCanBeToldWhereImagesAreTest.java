package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every setting the runtime reads has a way in from the artifact that ships.
 *
 * <p>Face images were built, proven by two classes, and reachable by nobody:
 * the runtime reads {@code dbo.face.images}, the launcher maps every setting
 * from a {@code DBO_} environment variable, and that one was not in the list.
 * So the only callers of the switch in the repository were the tests that
 * proved the feature works, and every deployment expanded the whole of a
 * version once per tenant — five times the bring-up, measured on the sample
 * world at 521 seconds against 106.
 *
 * <p>That is this repository's characteristic defect wearing a different
 * coat, so it gets the guard the others have. The check is deliberately
 * dumb — the launcher is a shell script and this reads it — because what
 * went wrong was not subtle.
 */
class TheDistributionCanBeToldWhereImagesAreTest {

    private static final Path LAUNCHER =
            Path.of("../dbo-server/src/main/dist/bin/dbo-server");

    @Test
    @DisplayName("the launcher passes every setting the runtime reads, images included")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void theLauncherPassesTheImageDirectory() throws Exception {
        String launcher = Files.readString(LAUNCHER);

        assertTrue(launcher.contains("dbo.face.images"),
                "the runtime reads dbo.face.images and the launcher cannot set it, so a "
                        + "deployment has no way to ask for images and every tenant expands "
                        + "the whole version again");
        assertTrue(launcher.contains("DBO_FACE_IMAGES"),
                "a setting arrives as an environment variable named for it, like every "
                        + "other one here: " + LAUNCHER);
    }
}
