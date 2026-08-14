package cloud.jengu.dbo.subscriptions;

import java.util.List;

/** Supplies the active subscriptions (implemented by the personality wiring). */
@FunctionalInterface
public interface SubscriptionSource {
    List<SubscriptionSpec> active();
}
