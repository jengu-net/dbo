package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.maintenance.ImportLedger;
import cloud.jengu.dbo.policy.PolicyObjectStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tenant writes down what it accepted, in its own accountability trail
 * (REQ-DBO-MNT-ACCEPTED-ROOT-RECORDED).
 *
 * <p>The record is an audit entry rather than a table of our own: the audit
 * trail is already append-only by policy, already stamps who and when, already
 * rides the feed, and already leaves with the tenant when the tenant leaves.
 * A private table beside it would have to earn each of those again, and would
 * be the one thing a departing customer's export did not include.
 *
 * <p>Coded detail only — a root, two key fingerprints and two counts. Nothing
 * here names a person or a payload, which is what lets the entry stay readable
 * to whoever audits the move.
 */
public final class AuditedImportLedger implements ImportLedger {

    /** The event code an auditor looks for when asking what was ever imported. */
    public static final String CODE = "archive-accepted";

    private final PolicyObjectStore store;

    public AuditedImportLedger(PolicyObjectStore store) {
        this.store = store;
    }

    @Override
    public void accepted(Accepted accepted) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("root", accepted.root());
        detail.put("vendorKey", accepted.vendorKeyDigest());
        detail.put("tenantKey", accepted.tenantKeyDigest());
        detail.put("objects", Long.toString(accepted.objects()));
        detail.put("unchanged", Long.toString(accepted.unchanged()));
        // The root is the target: an archive is not an object in the store, and
        // the thing being spoken about here is the archive, not what it carried.
        store.recordCustom(CODE, "Archive", accepted.root(), detail);
    }
}
