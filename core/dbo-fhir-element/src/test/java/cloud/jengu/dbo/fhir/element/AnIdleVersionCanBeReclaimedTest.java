package cloud.jengu.dbo.fhir.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A version's definitions are the largest thing this store holds that belongs
 * to nobody, and they can be given back.
 *
 * <p>Measured: a context retains around 180 MB for R4 and a little over 200
 * for R5 and R6. Held hard, a node asked about a version once paid for it
 * until it stopped — and a test suite that touches all three spent 610 MB of
 * a two-gigabyte heap before any tenant existed, which is where the heap
 * failures were coming from.
 *
 * <p>Two properties, and the second is the one that makes the first safe:
 * while something is using a version it keeps getting the same one, and while
 * nothing is, the collector may take it and the next asking rebuilds it.
 */
class AnIdleVersionCanBeReclaimedTest {

    @Test
    @DisplayName("a version in use is the same one every time it is asked for")
    void inUseItIsShared() {
        ElementVersion first = ElementVersion.of("r4");
        assertSame(first, ElementVersion.of("r4"),
                "a second asking rebuilt the definitions rather than sharing them, which is "
                        + "two hundred megabytes and several seconds nobody asked to spend");
    }

    @Test
    @DisplayName("an idle version is given back under memory pressure, and asking again "
            + "rebuilds it")
    void idleItIsReclaimable() {
        // Held softly, so this reference alone must not keep it alive.
        SoftReference<ElementVersion> idle =
                new SoftReference<>(ElementVersion.of("r4"));
        assertNotNull(idle.get(), "the fixture needs the version it just built");

        // Ask for memory until the collector takes what nothing is using. A
        // soft reference is cleared before an OutOfMemoryError is thrown, so
        // this either clears it or the JVM has room to spare — and running out
        // is the failure this property exists to prevent.
        boolean cleared = false;
        List<byte[]> pressure = new ArrayList<>();
        try {
            while (pressure.size() < 4096) {
                if (idle.get() == null) {
                    cleared = true;
                    break;
                }
                pressure.add(new byte[8 * 1024 * 1024]);
            }
        } catch (OutOfMemoryError expected) {
            cleared = idle.get() == null;
        } finally {
            pressure.clear();
        }

        assertTrue(cleared,
                "the definitions survived every byte the heap had left, so a version nobody "
                        + "is using cannot be given back and the memory is gone for the life "
                        + "of the process");
        assertNotNull(ElementVersion.of("r4"),
                "and asking again has to rebuild it rather than answering with nothing");
    }
}
