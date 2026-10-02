package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.spring.server.DboObserver;
import cloud.jengu.dbo.tenant.api.Change;
import cloud.jengu.dbo.tenant.api.TenantDomain;
import cloud.jengu.dbo.tenant.api.TenantFacts;
import cloud.jengu.dbo.tenant.api.TenantObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The work of every tenant that declares steps, read as it changes.
 *
 * <p>A named durable consumer rather than a callback, and the name is the
 * point: an application that was down for an hour resumes where it left off
 * instead of missing the hour. Changing the name starts again from the
 * beginning of the stream.
 *
 * <p>What arrives is THAT something changed — the type, the id, the version,
 * when. The work stream carries its content because it is the machinery's own
 * bookkeeping; a tenant's records stream does not, and an observer of it
 * reads the record through a step like anybody else.
 *
 * <p>Only those tenants: a consumer is something the store keeps a cursor for
 * and hands changes to, and a tenant whose work is not this application's
 * business — one that declares no step — is not worth one.
 *
 * <p>It keeps a count per tenant, which is what a status screen shows. A batch
 * that throws is left unacknowledged and arrives again rather than being
 * skipped, so nothing here can stop a tenant serving.
 */
// --8<-- [start:watching]
@Component
@DboObserver(domain = TenantDomain.WORK, consumer = "sample-clinic-watching-the-work",
        target = "(dbo.tenant.hasSteps=true)")
public final class WatchingTheWork implements TenantObserver {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.sample.work");

    private final Map<String, AtomicLong> seen = new ConcurrentHashMap<>();

    @Override
    public void observed(TenantFacts tenant, List<Change> changes) {
        seen.computeIfAbsent(tenant.code(), code -> new AtomicLong()).addAndGet(changes.size());
        LOG.debug("work changed: tenant={} changes={}", tenant.code(), changes.size());
    }

    /** How many changes to a tenant's work this application has read. */
    public long seen(String tenant) {
        AtomicLong count = seen.get(tenant);
        return count == null ? 0 : count.get();
    }
}
// --8<-- [end:watching]
