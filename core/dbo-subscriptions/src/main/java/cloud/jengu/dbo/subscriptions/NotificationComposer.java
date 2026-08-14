package cloud.jengu.dbo.subscriptions;

import cloud.jengu.dbo.core.api.feed.FeedItem;

/**
 * Builds the wire notification for one matched event — the personality owns
 * the shape (R5: subscription-notification Bundle + SubscriptionStatus;
 * R4 backport: history Bundle + Parameters status).
 */
@FunctionalInterface
public interface NotificationComposer {
    String compose(TopicSubscription subscription, TopicSpec topic, FeedItem item, long eventNumber);
}
