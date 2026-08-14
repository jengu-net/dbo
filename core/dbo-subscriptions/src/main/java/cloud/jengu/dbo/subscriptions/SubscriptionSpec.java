package cloud.jengu.dbo.subscriptions;

/** An active subscription, personality-parsed: id, criteria string, delivery endpoint. */
public record SubscriptionSpec(String id, String criteria, String endpoint) {}
