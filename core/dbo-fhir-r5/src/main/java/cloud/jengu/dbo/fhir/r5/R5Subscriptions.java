package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.subscriptions.NotificationComposer;
import cloud.jengu.dbo.subscriptions.TopicSpec;
import cloud.jengu.dbo.subscriptions.TopicSubscription;
import cloud.jengu.dbo.subscriptions.TopicSubscriptionSource;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.Subscription;
import org.hl7.fhir.r5.model.SubscriptionStatus;
import org.hl7.fhir.r5.model.SubscriptionTopic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * R5-native topic-based subscriptions (dbo#15): topics are stored active
 * SubscriptionTopic resources; subscriptions are R5 Subscription resources
 * (topic canonical + filterBy + rest-hook channel); notifications are
 * subscription-notification Bundles with SubscriptionStatus. Public surface
 * stays HAPI-free (§7.3).
 */
public final class R5Subscriptions {

    private R5Subscriptions() {}

    public static TopicSubscriptionSource topicSource(ObjectStore store, R5Personality personality) {
        return new TopicSubscriptionSource() {
            @Override
            public List<TopicSpec> topics() {
                Criteria active = personality
                        .compileSearch("SubscriptionTopic", Map.of("status", "active")).criteria();
                List<TopicSpec> out = new ArrayList<>();
                for (StoredObject o : store.select(active)) {
                    SubscriptionTopic topic = (SubscriptionTopic) personality.ctxInternal()
                            .newJsonParser()
                            .parseResource(new String(o.payload(), StandardCharsets.UTF_8));
                    if (topic.getResourceTrigger().isEmpty()) {
                        continue;
                    }
                    // slice: the first resource trigger defines the topic
                    var trigger = topic.getResourceTriggerFirstRep();
                    String resource = trigger.getResource();
                    String type = resource.contains("/")
                            ? resource.substring(resource.lastIndexOf('/') + 1) : resource;
                    Set<ChangeKind> interactions = new LinkedHashSet<>();
                    trigger.getSupportedInteraction().forEach(i -> {
                        switch (i.getValue()) {
                            case CREATE -> interactions.add(ChangeKind.CREATED);
                            case UPDATE -> interactions.add(ChangeKind.UPDATED);
                            case DELETE -> interactions.add(ChangeKind.DELETED);
                            default -> { }
                        }
                    });
                    Set<String> filterParams = new LinkedHashSet<>();
                    topic.getCanFilterBy().forEach(f -> filterParams.add(f.getFilterParameter()));
                    out.add(new TopicSpec(topic.getUrl(), type, interactions, filterParams));
                }
                return out;
            }

            @Override
            public List<TopicSubscription> activeTopicSubscriptions() {
                Criteria active = personality
                        .compileSearch("Subscription", Map.of("status", "active")).criteria();
                List<TopicSubscription> out = new ArrayList<>();
                for (StoredObject o : store.select(active)) {
                    Subscription sub = (Subscription) personality.ctxInternal().newJsonParser()
                            .parseResource(new String(o.payload(), StandardCharsets.UTF_8));
                    boolean restHook = "rest-hook".equals(sub.getChannelType().getCode());
                    if (!restHook || !sub.hasTopic() || !sub.hasEndpoint()) {
                        continue;
                    }
                    Map<String, String> filters = new LinkedHashMap<>();
                    sub.getFilterBy().forEach(f -> filters.put(f.getFilterParameter(), f.getValue()));
                    boolean idOnly = sub.hasContent()
                            && sub.getContent() == org.hl7.fhir.r5.model.Subscription.SubscriptionPayloadContent.IDONLY;
                    out.add(new TopicSubscription(o.id(), sub.getTopic(), sub.getEndpoint(),
                            filters, idOnly));
                }
                return out;
            }
        };
    }

    /** subscription-notification Bundle: SubscriptionStatus first, focus entry per content mode. */
    public static NotificationComposer composer(R5Personality personality) {
        return (subscription, topic, item, eventNumber) -> {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.SUBSCRIPTIONNOTIFICATION);

            SubscriptionStatus status = new SubscriptionStatus();
            status.setStatus(org.hl7.fhir.r5.model.Enumerations.SubscriptionStatusCodes.ACTIVE);
            status.setType(SubscriptionStatus.SubscriptionNotificationType.EVENTNOTIFICATION);
            status.setEventsSinceSubscriptionStart(eventNumber);
            status.setSubscription(new Reference("Subscription/" + subscription.id()));
            status.setTopic(topic.url());
            String focusRef = item.typeName() + "/" + item.objectId();
            var event = status.addNotificationEvent();
            event.setEventNumber(eventNumber);
            event.setFocus(new Reference(focusRef));
            bundle.addEntry().setResource(status)
                    .setFullUrl("urn:uuid:" + java.util.UUID.randomUUID());

            if (!subscription.idOnly() && item.kind() != ChangeKind.DELETED && item.payload() != null) {
                Resource focus = (Resource) personality.ctxInternal().newJsonParser()
                        .parseResource(new String(item.payload(), StandardCharsets.UTF_8));
                focus.setId(item.objectId());
                bundle.addEntry().setResource(focus).setFullUrl(focusRef);
            } else {
                bundle.addEntry().setFullUrl(focusRef);
            }
            return personality.ctxInternal().newJsonParser().encodeResourceToString(bundle);
        };
    }

    /** Filters compile through the personality's strict search compiler. */
    public static BiFunction<String, Map<String, String>, Criteria> filterCompiler(R5Personality personality) {
        return (resourceType, filters) -> personality.compileSearch(resourceType, filters).criteria();
    }

    /** Legacy criteria-string compiler for the R5 engine's non-topic path. */
    public static java.util.function.Function<String, Criteria> criteriaCompiler(R5Personality personality) {
        return criteriaString -> {
            int q = criteriaString.indexOf('?');
            String type = q < 0 ? criteriaString : criteriaString.substring(0, q);
            Map<String, String> params = new LinkedHashMap<>();
            if (q >= 0 && q < criteriaString.length() - 1) {
                for (String pair : criteriaString.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    params.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            return personality.compileSearch(type, params).criteria();
        };
    }
}
