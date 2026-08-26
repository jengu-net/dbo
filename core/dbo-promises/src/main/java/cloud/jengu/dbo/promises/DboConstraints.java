package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Constraint;
import cloud.jengu.dbo.promise.Promise;

import java.util.List;

/**
 * Externally imposed rules the store fulfils — the regulation's own
 * paragraphs, each fulfilled by named promises. The classification declares
 * its promises; a promise never names its classifications.
 */
@Catalogue(namespace = "CON-DBO")
public enum DboConstraints implements Constraint {

    GDPR_ERASURE("GDPR Art. 17 — the right to erasure: a person's data is gone on "
            + "request, provably, everywhere at once, without rewriting history.",
            List.of(DboPromises.PDI_CRYPTO_SHREDDING,
                    DboPromises.PDI_UNFINDABLE_AFTER_ERASURE,
                    DboPromises.PDI_SHRED_LEDGER)),

    GDPR_BY_DESIGN("GDPR Art. 25 — data protection by design and by default: isolation "
            + "is the architecture's property, not an operator's discipline.",
            List.of(DboPromises.PDI_STRUCTURAL_VAULT,
                    DboPromises.PDI_BLIND_OPERATIONS)),

    GDPR_SUBJECT_RIGHTS("GDPR Arts. 15, 18, 20 — access, restriction and portability "
            + "are operations, answerable on demand.",
            List.of(DboPromises.PDI_RIGHTS_AS_OPERATIONS));

    private final String title;
    private final List<Promise> promises;

    DboConstraints(String title, List<Promise> promises) {
        this.title = title;
        this.promises = promises;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public List<Promise> promises() {
        return promises;
    }
}
