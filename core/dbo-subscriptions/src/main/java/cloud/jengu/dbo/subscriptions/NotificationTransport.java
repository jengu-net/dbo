package cloud.jengu.dbo.subscriptions;

/** Delivers one notification; throwing triggers the durable retry policy. */
@FunctionalInterface
public interface NotificationTransport {
    void deliver(String endpoint, String notificationJson) throws Exception;
}
