package cloud.jengu.dbo.subscriptions;

import java.util.List;

/** Supplies topics and their active subscriptions (personality wiring). */
public interface TopicSubscriptionSource {

    List<TopicSpec> topics();

    List<TopicSubscription> activeTopicSubscriptions();
}
