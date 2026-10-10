package cloud.jengu.dbo.embedded;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a container keeps its bundle cache, asked of the one call every
 * assembly makes. Two processes cannot be started from here, so what is
 * asserted is the thing that keeps them apart: the directory is named for
 * this process, not for the name it was asked for.
 */
class AProcessKeepsItsContainerCacheToItselfTest {

    @Test
    @DisplayName("a container's cache is kept in a directory of this process's own, the same "
            + "one each time this process asks")
    @Proving(DboPromises.CONT_A_PROCESS_KEEPS_ITS_CONTAINER_CACHE_TO_ITSELF)
    void theCacheIsThisProcesssOwn(@TempDir Path temp) {
        Path asked = EmbeddedRuntime.storageUnder(temp, "dbo-embedded");

        assertNotEquals(temp.resolve("dbo-embedded"), asked,
                "every process given this name would share one cache");
        assertTrue(asked.getFileName().toString()
                        .endsWith("-" + ProcessHandle.current().pid()),
                "the cache is not named for the process that keeps it: " + asked);
        assertTrue(Files.isDirectory(asked));
        assertEquals(asked, EmbeddedRuntime.storageUnder(temp, "dbo-embedded"),
                "one process asking twice was given two caches");
    }
}
