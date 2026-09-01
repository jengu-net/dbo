package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declared capability is a translator, so it holds nothing to act with.
 *
 * <p>The face contract draws its line not at scope but at whether a thing
 * holds a store, and that is what decides where an obligation belongs. A
 * version-scoped pure function is declared here. Something that needs the
 * tenant's data — reassembling a vocabulary from the concepts this tenant
 * holds — is a facade the version constructs, and is never declared. A
 * per-request fact is not a scope at all and travels beside the request.
 *
 * <p>The rule earns a ratchet because breaking it is silent. A capability
 * holding a store is an actor: it can write inside an engine transaction, and
 * two faces over one store can then disagree about who did what. Nothing about
 * that fails at declaration. It works — until the day two of them run at once,
 * which is neither the day it was written nor the day anybody would look here.
 *
 * <p>A lambda that captured a store carries it as a field like anything else,
 * which is what makes the line visible rather than a convention people
 * remember. Direct fields only: a handle one hop away is a different argument,
 * and drawing this one where it can be seen beats drawing it where it cannot.
 */
class ADeclaredCapabilityHoldsNoStoreTest {

    /** Handles whose holder is acting rather than translating. */
    private static final List<Class<?>> ACTING_HANDLES =
            List.of(ObjectStore.class, javax.sql.DataSource.class, Connection.class);

    @Test
    @DisplayName("no capability any installed face declares holds a store handle")
    void everyDeclaredCapabilityIsATranslator() {
        List<String> offences = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (String code : FhirVersions.installed().codes()) {
            DomainFace face = FhirVersions.installed().require(code).face();
            for (Class<?> type : face.capabilities()) {
                Object capability = face.capability(type).orElseThrow();
                checked.add(code + "/" + type.getSimpleName());
                for (Field field : capability.getClass().getDeclaredFields()) {
                    if (acting(field.getType())) {
                        offences.add(code + ": " + type.getSimpleName() + " holds "
                                + field.getType().getSimpleName() + " as '"
                                + field.getName() + "'");
                    }
                }
            }
        }

        assertTrue(checked.size() >= 6,
                "almost no declared capabilities were reachable, so this is guarding "
                        + "nothing: " + checked);
        assertTrue(offences.isEmpty(),
                "a declared capability holds a store, which makes it an actor rather than "
                        + "a translator: it can write inside an engine transaction, and two "
                        + "faces over one store can then disagree about who did what. What "
                        + "needs the tenant's data is a facade the version constructs, never "
                        + "a declared capability.\n  " + String.join("\n  ", offences));
    }

    private static boolean acting(Class<?> type) {
        return ACTING_HANDLES.stream().anyMatch(handle -> handle.isAssignableFrom(type));
    }
}
