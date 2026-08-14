package cloud.jengu.dbo.pdi;

import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.util.List;
import java.util.Set;

/**
 * Registration transform (§14.2): under PDI, person types become INTERNAL
 * to the inner engine — their payloads reach it stripped, so envelope-based
 * identity would claim nothing. Identity semantics move to the vault's HMAC
 * claims, enforced by {@link PdiObjectStore}. Everything else is untouched.
 */
public final class PdiSetup {

    private PdiSetup() {
    }

    public static List<TypeRegistration> transform(List<TypeRegistration> registrations, PdiSpec spec) {
        return registrations.stream()
                .map(r -> spec.isPersonType(r.typeName())
                        ? new TypeRegistration(r.typeName(), r.domain(), IdentityClass.INTERNAL,
                                Set.of(), r.extractor(), r.indexes(), r.payloadVersion())
                        : r)
                .toList();
    }
}
