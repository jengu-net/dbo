package cloud.jengu.dbo.sample;

import cloud.jengu.dbo.tenant.Change;
import cloud.jengu.dbo.tenant.TenantFacts;
import cloud.jengu.dbo.tenant.TenantObserver;

import java.util.List;

/** A named durable consumer of one of a tenant's streams. */
public final class WatchingTheWork implements TenantObserver {

    @Override
    public void observed(TenantFacts tenant, List<Change> changes) {
        for (Change change : changes) {
            // What the stream carries is THAT something changed — the type,
            // the id, the version, when. For a tenant's records and its trail
            // it does not carry what the record says.
            System.out.println(tenant.code() + " " + change.typeName()
                    + "/" + change.objectId() + " at v" + change.versionId());
        }
        // Throwing leaves the batch unacknowledged, so it arrives again rather
        // than being skipped. Nothing here can stop the tenant serving.
    }
}
