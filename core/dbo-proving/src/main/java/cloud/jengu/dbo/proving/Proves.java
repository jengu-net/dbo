package cloud.jengu.dbo.proving;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An assertion that names the promise it proves.
 *
 * <p>A test declares what it proves with {@link Proving}, and the catalogue
 * reads that: it is how a promise comes to read PROVEN and by which test. What
 * the annotation cannot say is which ASSERTION proved it — so a method
 * declaring three promises and failing one reports <i>expected true but was
 * false</i>, and whoever reads it has to work out which claim broke.
 *
 * <p>This leads the failure with the promise's code and the sentence the
 * catalogue holds, so a report says what the store stopped promising rather
 * than what a boolean was.
 *
 * <p><b>It cannot claim a promise the method did not declare.</b> The
 * annotation stays the declaration and this stays the proof, and the two are
 * checked against each other — otherwise there would be two places saying what
 * proves what, and the catalogue would be reading one of them while the tests
 * asserted the other.
 *
 * <p><b>What it deliberately does not do</b> is fail a method that declares a
 * promise no assertion here proved. That guard is worth having and is a
 * different change: it would fail tests that declare honestly and assert
 * through helpers, and it belongs beside the other ledgers rather than inside
 * an assertion.
 */
public final class Proves {

    private Proves() {
    }

    /** One claim, named. */
    public static void that(DboPromises promise, boolean held, String message) {
        declared(promise);
        assertTrue(held, () -> leading(promise) + message);
    }

    /**
     * Several claims about one promise, reported together.
     *
     * <p>Grouped because a promise is usually several assertions and a bring-up
     * costs minutes: stopping at the first disagreement spends them again for
     * the second.
     */
    public static void all(DboPromises promise, Executable... claims) {
        declared(promise);
        assertAll(leading(promise), claims);
    }

    /** The promise, as a report should lead with it. */
    private static String leading(DboPromises promise) {
        return promise.code() + System.lineSeparator()
                + "  " + promise.text() + System.lineSeparator();
    }

    /**
     * Refuses a promise the running test did not declare.
     *
     * <p>Walked off the stack rather than passed in, because a test that had to
     * hand its own method to an assertion would be free to hand it another's —
     * and the one thing this guard is for is that the declaration and the proof
     * are the same test's.
     */
    private static void declared(DboPromises promise) {
        List<DboPromises> claims = new ArrayList<>();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            Method method = methodOf(frame);
            if (method == null) {
                continue;
            }
            Proving proving = method.getAnnotation(Proving.class);
            if (proving == null) {
                proving = method.getDeclaringClass().getAnnotation(Proving.class);
            }
            if (proving != null) {
                claims.addAll(Arrays.asList(proving.value()));
                if (claims.contains(promise)) {
                    return;
                }
            }
        }
        throw new AssertionError(promise.code() + " is asserted here and no test on this stack "
                + "declares it. @Proving is what the catalogue reads, so a promise proved and "
                + "not declared reads as unproven, and one declared and proved elsewhere reads "
                + "as proved by the wrong test. Declared on this stack: "
                + (claims.isEmpty() ? "nothing" : claims));
    }

    /** The method a frame names, or null where it cannot be resolved. */
    private static Method methodOf(StackTraceElement frame) {
        try {
            for (Method candidate : Class.forName(frame.getClassName()).getDeclaredMethods()) {
                if (candidate.getName().equals(frame.getMethodName())) {
                    return candidate;
                }
            }
        } catch (ClassNotFoundException | LinkageError notResolvable) {
            return null;
        }
        return null;
    }
}
