package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.work.StepScheduler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A scheduler that tells a tick when the test says so, to whichever tenant
 * opened it — a hosting framework's own, as far as the store can tell, so
 * nobody waits out a tick.
 */
final class HandCrankedScheduler implements StepScheduler {

    /** Each tenant's tick, while its clock is open. */
    final Map<String, Tick> opened = new ConcurrentHashMap<>();

    /** The rules each tenant's clock was last given. */
    final Map<String, Map<String, String>> rules = new ConcurrentHashMap<>();

    @Override
    public Clock open(String tenant, javax.sql.DataSource database, Tick tick) {
        opened.put(tenant, tick);
        return new Clock() {
            @Override
            public void apply(Map<String, String> applied) {
                rules.put(tenant, applied);
            }

            @Override
            public void close() {
                opened.remove(tenant, tick);
            }
        };
    }
}
