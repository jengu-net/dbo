package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.feed.Watching;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The searches a tenant's steps that start on a change, or at a moment a
 * record names, are watched with — known from the declaration before the
 * tenant's feed is first read, and handed to its self-starting steps once
 * they are standing.
 *
 * <p>Given to every tenant with a feed that is matched, searches or none: a
 * match recovered from before a step stopped starting itself here is handed over and answered
 * with nothing.
 */
final class StepWatches implements Watching {

    private final boolean place;
    private final CompletableFuture<SelfStarting> starting = new CompletableFuture<>();
    private volatile Map<String, String> searches;

    StepWatches(List<TenantSpec.Step> steps, boolean place) {
        this.place = place;
        redeclared(steps);
    }

    void redeclared(List<TenantSpec.Step> steps) {
        Map<String, String> watched = new LinkedHashMap<>();
        for (TenantSpec.Step step : SelfStarting.startingHere(steps, place).values()) {
            TenantSpec.Starts starts = step.starts();
            String search = starts.change() != null ? starts.change() : starts.due();
            if (search != null) {
                watched.put(step.code(), search);
            }
        }
        searches = Map.copyOf(watched);
    }

    /** The self-starting steps standing, which every match is handed to from now. */
    void attach(SelfStarting standing) {
        starting.complete(standing);
    }

    /** Nobody will attach: this tenant starts nothing on a change. */
    void detached() {
        starting.complete(null);
    }

    @Override
    public Map<String, String> searches() {
        return searches;
    }

    @Override
    public Instant matched(String watch, String type, String id, long version) {
        return matched(watch, type, id, version, null);
    }

    @Override
    public Instant matched(String watch, String type, String id, long version, String run) {
        SelfStarting standing = starting.join();
        return standing == null ? null : standing.matched(watch, type, id, version, run);
    }
}
