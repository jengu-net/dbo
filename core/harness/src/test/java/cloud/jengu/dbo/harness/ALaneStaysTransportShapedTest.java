package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The lane stays implementable from somewhere else.
 *
 * <p>{@code Lane} is the whole of what a runner can do, and the contract is
 * that a remote implementation and the in-process one are indistinguishable to
 * it. That holds only while every verb is expressible as a message — so a verb
 * that hands over a live handle, or one a remote lane could inherit without
 * writing, breaks the contract at the moment it is added rather than when
 * somebody far away tries to build against it.
 *
 * <p>Both rules are already written in {@code Lane}'s own javadoc, twice each,
 * as prose addressed to whoever adds the next verb. Prose does not fail a
 * build.
 */
class ALaneStaysTransportShapedTest {

    /**
     * Types whose behaviour lives on the host's side of the line. A verb
     * mentioning one cannot be served from another machine — there is nothing
     * to send.
     */
    private static final Set<String> HANDLES = Set.of(
            "cloud.jengu.dbo.core.api.ObjectStore",
            "cloud.jengu.dbo.core.api.feed.ChangeFeed",
            "cloud.jengu.dbo.work.Runs",
            "cloud.jengu.dbo.work.Declarations",
            "cloud.jengu.dbo.work.Introductions",
            "cloud.jengu.dbo.work.Participation",
            "javax.sql.DataSource",
            "java.sql.Connection");

    @Test
    @DisplayName("no verb takes or returns a live handle — a remote lane has nothing to send "
            + "in its place")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void everyVerbIsExpressibleAsAMessage() {
        // Declarations.Declared is deliberately fine and Declarations is not:
        // the record is data a lane can carry, the service is the host's.
        assertEquals(List.of(), handlesNamedBy(verbs()),
                "a verb naming a handle cannot be served from another machine");
    }

    /**
     * The detector, on something that does leak.
     *
     * <p>A ratchet nobody has watched fail is a ratchet nobody knows the shape
     * of. {@code Lane} itself cannot be mutated to prove this — every signature
     * change breaks its in-process implementation before the test runs — so the
     * rule is demonstrated against a fixture instead.
     */
    @Test
    @DisplayName("and the rule would catch one: a leaking verb is named, with the handle it leaks")
    void theRuleCatchesALeak() {
        List<String> caught = handlesNamedBy(List.of(Leaky.class.getDeclaredMethods()));

        assertEquals(List.of("theStoreItself mentions cloud.jengu.dbo.core.api.ObjectStore",
                        "workWrappedInAHandle mentions cloud.jengu.dbo.work.Runs"),
                caught.stream().sorted().toList());
    }

    /** A lane nobody could serve from another machine, for the test above. */
    private interface Leaky {

        cloud.jengu.dbo.core.api.ObjectStore theStoreItself();

        List<cloud.jengu.dbo.work.Runs> workWrappedInAHandle();
    }

    private static List<String> handlesNamedBy(List<Method> verbs) {
        List<String> offenders = new ArrayList<>();
        for (Method verb : verbs) {
            List<Type> mentioned = new ArrayList<>();
            mentioned.add(verb.getGenericReturnType());
            mentioned.addAll(List.of(verb.getGenericParameterTypes()));
            for (Type type : mentioned) {
                names(type).stream().filter(HANDLES::contains).forEach(handle ->
                        offenders.add(verb.getName() + " mentions " + handle));
            }
        }
        return offenders;
    }

    @Test
    @DisplayName("no verb is defaulted — a lane that inherits a verb is a lane that can drop "
            + "it silently")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void noVerbCanBeInherited() {
        List<String> defaulted = verbs().stream()
                .filter(Method::isDefault)
                .map(Method::getName)
                .toList();

        // milestone and introduce are abstract for exactly this reason, each
        // with a paragraph saying so (#150, #147): a default degrading
        // milestone to a checkpoint, or swallowing an introduction, passes
        // every test while losing the one thing the report said. The rule is
        // not about those two verbs — it is about the next one.
        assertEquals(List.of(), defaulted,
                "every lane must decide these itself; none may inherit a drop");
    }

    /** The interface's instance methods — its verbs. Static factories are not verbs. */
    private static List<Method> verbs() {
        List<Method> verbs = new ArrayList<>();
        for (Method m : Lane.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                verbs.add(m);
            }
        }
        // Guards against passing vacuously: if the verbs ever move to a
        // superinterface, getDeclaredMethods returns nothing and both rules
        // above become true of an empty list.
        assertEquals(13, verbs.size(),
                "the verb count changed; a new verb is exactly what these rules are for");
        return verbs;
    }

    /** Every class named by a type, including the arguments of a generic one. */
    private static List<String> names(Type type) {
        List<String> named = new ArrayList<>();
        if (type instanceof Class<?> raw) {
            named.add(raw.getName());
        } else if (type instanceof ParameterizedType parameterized) {
            named.addAll(names(parameterized.getRawType()));
            for (Type argument : parameterized.getActualTypeArguments()) {
                named.addAll(names(argument));
            }
        }
        return named;
    }
}
