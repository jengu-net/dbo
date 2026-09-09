package cloud.jengu.dbo.pdi;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registration transform (§14.2): under PDI, person types become INTERNAL
 * to the inner engine — their payloads reach it stripped, so envelope-based
 * identity would claim nothing. Identity semantics move to the vault's HMAC
 * claims, enforced by {@link PdiObjectStore}. Everything else is untouched.
 *
 * <p>Which means the declaration has to move WITH them. What a type is
 * identified by is the tenant's word, it is on the registration before this
 * runs, and this is the last place it exists — after the transform every
 * person type looks alike and reads INTERNAL with no systems. Handing identity
 * to the vault without handing over what identifies is what made the vault
 * claim for every person type it knew: a human held as a Person and as a
 * Patient, which is the ordinary shape and one a tenant declares, collided
 * with itself on whichever record carried the number second.
 */
public final class PdiSetup {

    private PdiSetup() {
    }

    /**
     * What each person type is identified BY, before the transform forgets.
     *
     * <p>A person type the tenant declared INTERNAL appears with no systems,
     * and that is the answer rather than a gap: nothing identifies it, so it
     * claims nothing and merely carries whatever numbers it carries. Those are
     * still indexed, because finding somebody by a value is what replaces
     * plaintext search once the plaintext is gone — it is refusing a second
     * record the same value that is a policy, and the tenant makes it.
     */
    public static Map<String, Set<String>> identifiedBy(List<TypeRegistration> registrations,
            PdiSpec spec) {
        Map<String, Set<String>> declared = new LinkedHashMap<>();
        for (TypeRegistration r : registrations) {
            if (spec.isPersonType(r.typeName())) {
                declared.put(r.typeName(), Set.copyOf(r.identitySystems()));
            }
        }
        return Map.copyOf(declared);
    }

    public static List<TypeRegistration> transform(List<TypeRegistration> registrations, PdiSpec spec) {
        return registrations.stream()
                .map(r -> spec.isPersonType(r.typeName())
                        ? new TypeRegistration(r.typeName(), r.domain(), IdentityClass.INTERNAL,
                                Set.of(), Handling.operational(), r.extractor(), r.indexes(), r.payloadVersion())
                        : r)
                .toList();
    }
}
