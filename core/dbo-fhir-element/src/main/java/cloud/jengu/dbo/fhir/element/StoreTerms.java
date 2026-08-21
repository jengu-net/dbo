package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.terminology.TerminologyStore;

import java.util.Optional;

/**
 * {@link Terms} answered by the tenant's own {@link TerminologyStore} — the
 * native form every tenant holds (REQ-DBO-TERM-EVERY-TENANT-ANSWERS), now also
 * the authority validation consults (REQ-DBO-TERM-VALIDATION-USES-TENANT-TERMINOLOGY).
 *
 * <p>Held-or-not comes from the system registry, not from counting concepts:
 * a system imported empty is a held system whose codes are all absent, which
 * is a definitive answer rather than a coverage gap.
 */
final class StoreTerms implements Terms {

    private final TerminologyStore store;

    StoreTerms(TerminologyStore store) {
        this.store = store;
    }

    @Override
    public Optional<Membership> membership(String system, String code) {
        if (store.systemVersion(system).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(store.lookup(system, code)
                .map(concept -> new Membership(true, concept.display()))
                .orElseGet(() -> new Membership(false, null)));
    }
}
