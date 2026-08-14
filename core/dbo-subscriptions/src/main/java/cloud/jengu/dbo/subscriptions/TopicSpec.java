package cloud.jengu.dbo.subscriptions;

import cloud.jengu.dbo.core.api.feed.ChangeKind;

import java.util.Set;

/**
 * A subscription topic: what can be subscribed to (dbo#15). In R5 this is a
 * stored SubscriptionTopic resource; in the R4 backport, platform
 * configuration — either way the engine sees this neutral form.
 */
public record TopicSpec(
        String url,
        String resourceType,
        Set<ChangeKind> interactions,
        Set<String> allowedFilterParams) {

    public TopicSpec {
        interactions = Set.copyOf(interactions);
        allowedFilterParams = Set.copyOf(allowedFilterParams);
    }
}
