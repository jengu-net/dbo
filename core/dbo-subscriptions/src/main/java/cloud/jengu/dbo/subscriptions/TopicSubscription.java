package cloud.jengu.dbo.subscriptions;

import java.util.Map;

/**
 * An active topic-based subscription in neutral form. {@code filters} must
 * lie within the topic's {@code allowedFilterParams} — the source validates
 * and a nonconforming subscription delivers nothing.
 */
public record TopicSubscription(
        String id,
        String topicUrl,
        String endpoint,
        Map<String, String> filters,
        boolean idOnly) {

    public TopicSubscription {
        filters = Map.copyOf(filters);
    }
}
