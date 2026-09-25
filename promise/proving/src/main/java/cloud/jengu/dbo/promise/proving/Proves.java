package cloud.jengu.dbo.promise.proving;

import cloud.jengu.dbo.promise.Cites;
import cloud.jengu.dbo.promise.Promise;
import org.junit.jupiter.api.function.Executable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An assertion that names the promise it proves.
 *
 * <p>A test declares what it proves with the product's own {@link Cites}
 * annotation, and the catalogue reads that: it is how a promise comes to read
 * PROVEN and by which test. What the annotation cannot say is which ASSERTION
 * proved it — so a method declaring three promises and failing one reports
 * <i>expected true but was false</i>, and whoever reads it has to work out
 * which claim broke.
 *
 * <p>This leads the failure with the promise's code and the sentence the
 * catalogue holds, so a report says what the product stopped promising rather
 * than what a boolean was.
 *
 * <p><b>It cannot claim a promise the method did not declare.</b> The
 * annotation stays the declaration and this stays the proof, and the two are
 * checked against each other — otherwise there would be two places saying what
 * proves what, and the catalogue would be reading one of them while the tests
 * asserted the other.
 *
 * <p><b>It knows no product's catalogue</b>, for the reason {@link Cites}
 * exists: it takes a {@link Promise} and recognises a declaration by the
 * marker on the annotation's type, reading {@code value()} reflectively — the
 * same way the registry and the report do. So this module names the framework
 * and JUnit, and nothing of the store it happens to be built beside.
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
    public static void that(Promise promise, boolean held, String message) {
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
    public static void all(Promise promise, Executable... claims) {
        declared(promise);
        assertAll(leading(promise), claims);
    }

    /** The promise, as a report should lead with it. */
    private static String leading(Promise promise) {
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
    private static void declared(Promise promise) {
        List<Promise> claims = new ArrayList<>();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            Method method = methodOf(frame);
            if (method == null) {
                continue;
            }
            claims.addAll(citedBy(method));
            claims.addAll(citedBy(method.getDeclaringClass()));
            if (claims.contains(promise)) {
                return;
            }
        }
        throw new AssertionError(promise.code() + " is asserted here and no test on this stack "
                + "declares it. The citation is what the catalogue reads, so a promise proved "
                + "and not declared reads as unproven, and one declared and proved elsewhere "
                + "reads as proved by the wrong test. Declared on this stack: "
                + (claims.isEmpty() ? "nothing" : claims.stream().map(Promise::code).toList()));
    }

    /** The promises an element cites, through any annotation marked {@link Cites}. */
    private static List<Promise> citedBy(java.lang.reflect.AnnotatedElement element) {
        List<Promise> cited = new ArrayList<>();
        for (Annotation annotation : element.getAnnotations()) {
            if (!annotation.annotationType().isAnnotationPresent(Cites.class)) {
                continue;
            }
            try {
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                for (Object one : (Object[]) value) {
                    if (one instanceof Promise named) {
                        cited.add(named);
                    }
                }
            } catch (ReflectiveOperationException | ClassCastException notACitation) {
                // A @Cites annotation whose value() is not a promise array is
                // the framework's own concern, not an assertion's: the
                // processor refuses it where it is declared.
            }
        }
        return cited;
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
