package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.spring.server.DboTenantListener;
import cloud.jengu.dbo.tenant.api.TenantFacts;
import cloud.jengu.dbo.tenant.api.TenantLifecycleListener;
import cloud.jengu.dbo.tenant.api.TenantPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Told when a tenant starts serving.
 *
 * <p>For what is rare and can be derived again if it is missed: registering
 * the tenant with something outside, warming a cache, putting it on a
 * screen. Anything that must not miss an event is an observer instead, which
 * resumes where it left off; a listener is told once, at the point it named.
 */
@Component
@DboTenantListener(point = TenantPoint.SERVING)
public final class NoticingATenant implements TenantLifecycleListener {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.sample.tenants");

    private final Set<String> noticed = ConcurrentHashMap.newKeySet();

    @Override
    public void reached(TenantPoint point, TenantFacts tenant) {
        noticed.add(tenant.code());
        LOG.info("noticed: tenant={} point={}", tenant.code(), point.spelling());
    }

    /** Whether this application has been told the tenant is serving. */
    public boolean noticed(String tenant) {
        return noticed.contains(tenant);
    }
}
