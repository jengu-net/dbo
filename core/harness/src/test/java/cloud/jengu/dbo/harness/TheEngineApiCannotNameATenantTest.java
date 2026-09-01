package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tenancy is structural: a store instance <b>is</b> one tenant's store, so
 * there is no tenant to pass and none to leave out.
 *
 * <p>The difference this ratchet defends is the whole of R3. A store that
 * takes a tenant identifier is a store where isolation is a filter somebody
 * applies — correct while everyone remembers, and one forgotten argument away
 * from a cross-tenant read that no type system objects to. A store that cannot
 * be told which tenant it is has no such argument to forget.
 *
 * <p>So the assertion is about what the engine's API can <i>express</i>, not
 * about what today's callers happen to do. The moment a signature offers a
 * caller a way to name a tenant, that possibility exists whether or not anyone
 * uses it yet, and it will be used — which is why this fails at the signature
 * rather than waiting for the read.
 *
 * <p>The behavioural half is {@link ATenantIsAStoreNotAFilterIT}: this says
 * the road cannot be built, that one drives to the end of it.
 */
class TheEngineApiCannotNameATenantTest {

    /** The exported engine API — what a consumer outside the engine may call. */
    private static final String API = "cloud/jengu/dbo/core/api/";

    /**
     * Anything that would let a caller say which tenant it means. Includes the
     * spellings a well-meaning addition would reach for, because the rule is
     * not about one word: a {@code database}, {@code schema} or {@code owner}
     * argument on a store operation is the same argument wearing a hat.
     */
    private static final List<String> NAMES_A_TENANT =
            List.of("tenant", "database", "schema", "datasource", "owner", "customer");

    @Test
    @DisplayName("no exported engine operation lets a caller say which tenant it means")
    @Proving(DboPromises.TEN_STRUCTURAL_SCOPING)
    void theApiOffersNoPlaceToPutATenant() throws Exception {
        List<String> offences = new ArrayList<>();

        for (Class<?> type : exportedApi()) {
            for (Method m : type.getDeclaredMethods()) {
                if (!isPublicApi(m, type)) {
                    continue;
                }
                flag(offences, type, "method " + m.getName(), m.getName());
                flagParameters(offences, type, "method " + m.getName(), m);
            }
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                if (!isPublicApi(c, type)) {
                    continue;
                }
                flagParameters(offences, type, "constructor", c);
            }
            for (Field f : type.getDeclaredFields()) {
                // An enum constant is a value in a closed vocabulary, not a
                // handle: Handling.Authority.SOURCE_TENANT classifies who
                // authored a record — "published by another tenant" — and
                // naming that is the opposite of offering a caller a tenant to
                // pass. Every other public field stays in scope, because a
                // `public static final String DEFAULT_TENANT` is exactly the
                // shape this rule exists to refuse.
                if (Modifier.isPublic(f.getModifiers()) && !f.isEnumConstant()) {
                    flag(offences, type, "field " + f.getName(), f.getName());
                }
            }
        }

        assertTrue(offences.isEmpty(),
                "the engine API can name a tenant, so isolation is a filter somebody "
                        + "must remember rather than a structure:\n  "
                        + String.join("\n  ", offences));
    }

    @Test
    @DisplayName("and the ratchet is looking at something — the API is not empty")
    void theScanFindsTheApiItClaimsToGuard() throws Exception {
        List<Class<?>> api = exportedApi();

        assertTrue(api.size() >= 20,
                "a scan over an empty or mis-located package would pass this ratchet "
                        + "while checking nothing; found " + api.size());
        assertTrue(api.stream().anyMatch(c -> "ObjectStore".equals(c.getSimpleName())),
                "the store interface is the type this rule exists for, and it was not "
                        + "in the scan");
    }

    /**
     * A type's own name may say "tenant" — the rule is about what a caller can
     * <i>pass</i> or <i>read</i>, not about vocabulary. Nothing in the exported
     * api package is named that way today; if something is added, this comment
     * is where the exemption gets argued rather than silently widened.
     */
    private static void flag(List<String> offences, Class<?> type, String what, String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String needle : NAMES_A_TENANT) {
            if (lower.contains(needle)) {
                offences.add(type.getSimpleName() + "." + what + " names '" + needle + "'");
                return;
            }
        }
    }

    private static void flagParameters(List<String> offences, Class<?> type,
            String what, Executable e) {
        for (Class<?> p : e.getParameterTypes()) {
            flag(offences, type, what + " takes " + p.getSimpleName(), p.getSimpleName());
        }
    }

    private static boolean isPublicApi(java.lang.reflect.Member m, Class<?> owner) {
        return Modifier.isPublic(m.getModifiers())
                || (owner.isInterface() && !Modifier.isPrivate(m.getModifiers()));
    }

    private static List<Class<?>> exportedApi() throws Exception {
        List<Class<?>> types = new ArrayList<>();
        try (JarFile jar = new JarFile(Path.of(System.getProperty("dbo.core.jar")).toFile())) {
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(API) || !name.endsWith(".class")) {
                    continue;
                }
                String binary = name.substring(0, name.length() - ".class".length())
                        .replace('/', '.');
                Class<?> type = Class.forName(binary, false,
                        TheEngineApiCannotNameATenantTest.class.getClassLoader());
                if (Modifier.isPublic(type.getModifiers())) {
                    types.add(type);
                }
            }
        }
        assertFalse(types.isEmpty(), "no exported api classes found in the core jar");
        return types;
    }
}
