package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.subscriptions.NotificationComposer;
import cloud.jengu.dbo.subscriptions.SubscriptionSource;
import cloud.jengu.dbo.subscriptions.SubscriptionSpec;
import cloud.jengu.dbo.subscriptions.TopicSpec;
import cloud.jengu.dbo.subscriptions.TopicSubscription;
import cloud.jengu.dbo.subscriptions.TopicSubscriptionSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Subscriptions on the face that actually serves tenants.
 *
 * <p>There were already two implementations of this, in the R4 and R5
 * personalities, and both hung off `R4Store` / `R5Store` — which no tenant
 * runs. `R4FhirVersion` delegates serving to the element store, and the only
 * production construction of `R4Store` is inside the terminology path for two
 * calls that have nothing to do with serving. So the engine was complete, the
 * personalities' halves were complete, and they were wired to each other
 * across a seam no request crosses. The reach ledger said <i>no composition
 * root knows subscriptions exist</i>; the sharper statement is that none
 * could, from where tenants are served.
 *
 * <p>So it lives here, where the requests are. The parts are not ported from
 * the personalities so much as assembled from what this face already does:
 * the criteria compiler is the one that compiles every search, and a
 * notification is a Bundle written the way this face writes every other
 * Bundle — as JSON, rather than through a model object that would pin it to
 * one FHIR version's classes.
 */
final class ElementSubscriptions {

    private ElementSubscriptions() {
    }

    /**
     * The active rest-hook subscriptions this tenant holds.
     *
     * <p>Read through the same compiler a search uses, so "active" means here
     * what it means at the door.
     */
    static SubscriptionSource source(ObjectStore store, ParametersInForce inForce) {
        return () -> {
            List<SubscriptionSpec> specs = new ArrayList<>();
            Criteria active;
            try {
                active = ElementSearch.compile(inForce, "Subscription",
                        Map.of("status", "active")).criteria();
            } catch (RuntimeException notServed) {
                // A tenant that does not serve Subscription holds none, which
                // is an ordinary answer rather than a reason to stop the
                // dispatcher for every other tenant on this node.
                return List.of();
            }
            for (StoredObject held : store.select(active)) {
                Object sub = Json.parse(new String(held.payload(), StandardCharsets.UTF_8));
                Object channel = ((Map<?, ?>) sub).get("channel");
                if (channel == null || !"rest-hook".equals(field(channel, "type"))) {
                    continue;
                }
                String criteria = field(sub, "criteria");
                String endpoint = field(channel, "endpoint");
                if (criteria != null && endpoint != null) {
                    // No payload mimetype on the channel is FHIR asking to be
                    // told THAT something changed rather than what it says —
                    // its own default, and the only shape that is safe across
                    // a plane which may not read identifying elements.
                    specs.add(new SubscriptionSpec(held.id(), criteria, endpoint,
                            field(channel, "payload") == null));
                }
            }
            return specs;
        };
    }

    /** {@code Type?name=value&…} → engine criteria, through the search compiler. */
    static Function<String, Criteria> criteriaCompiler(ParametersInForce inForce) {
        return criteria -> {
            int question = criteria.indexOf('?');
            String type = question < 0 ? criteria : criteria.substring(0, question);
            Map<String, String> params = new LinkedHashMap<>();
            if (question >= 0 && question < criteria.length() - 1) {
                for (String pair : criteria.substring(question + 1).split("&")) {
                    int equals = pair.indexOf('=');
                    if (equals <= 0) {
                        throw new IllegalArgumentException("invalid criteria pair: " + pair);
                    }
                    params.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
            return ElementSearch.compile(inForce, type, params).criteria();
        };
    }

    /**
     * Topics and the subscriptions on them, for a face whose version has a
     * `SubscriptionTopic` to declare one in.
     *
     * <p>A tenant that does not serve that type has no topics, which is an
     * ordinary answer rather than a gap: in R4 a topic is platform
     * configuration rather than a record, and there is nowhere to declare
     * one yet. Answering with none is the honest version of that — a topic
     * list invented here would be a matcher matching nothing, silently.
     */
    static TopicSubscriptionSource topicSource(ObjectStore store, ParametersInForce inForce) {
        return new TopicSubscriptionSource() {

            @Override
            public List<TopicSpec> topics() {
                List<TopicSpec> topics = new ArrayList<>();
                for (StoredObject held : select(store, inForce, "SubscriptionTopic")) {
                    Object topic = Json.parse(new String(held.payload(), StandardCharsets.UTF_8));
                    List<Object> triggers = Json.array(topic, "resourceTrigger");
                    if (triggers.isEmpty()) {
                        continue;
                    }
                    // One slice, as the personalities took it: the first
                    // resource trigger defines the topic.
                    Object trigger = triggers.get(0);
                    String resource = field(trigger, "resource");
                    if (resource == null) {
                        continue;
                    }
                    Set<ChangeKind> interactions = new LinkedHashSet<>();
                    for (String interaction : Json.strings(trigger, "supportedInteraction")) {
                        switch (interaction) {
                            case "create" -> interactions.add(ChangeKind.CREATED);
                            case "update" -> interactions.add(ChangeKind.UPDATED);
                            case "delete" -> interactions.add(ChangeKind.DELETED);
                            default -> { }
                        }
                    }
                    Set<String> canFilterBy = new LinkedHashSet<>();
                    for (Object filter : Json.array(topic, "canFilterBy")) {
                        String parameter = field(filter, "filterParameter");
                        if (parameter != null) {
                            canFilterBy.add(parameter);
                        }
                    }
                    topics.add(new TopicSpec(field(topic, "url"),
                            resource.contains("/")
                                    ? resource.substring(resource.lastIndexOf('/') + 1) : resource,
                            interactions, canFilterBy));
                }
                return topics;
            }

            @Override
            public List<TopicSubscription> activeTopicSubscriptions() {
                List<TopicSubscription> subscriptions = new ArrayList<>();
                for (StoredObject held : select(store, inForce, "Subscription")) {
                    Object sub = Json.parse(new String(held.payload(), StandardCharsets.UTF_8));
                    // The R5 spelling. A criteria subscription carries
                    // `channel` and `criteria` instead and is served by the
                    // other half of this file — each answers nothing for the
                    // other's shape rather than guessing at it.
                    Object channelType = ((Map<?, ?>) sub).get("channelType");
                    String topic = field(sub, "topic");
                    String endpoint = field(sub, "endpoint");
                    if (channelType == null || topic == null || endpoint == null
                            || !"rest-hook".equals(field(channelType, "code"))) {
                        continue;
                    }
                    Map<String, String> filters = new java.util.LinkedHashMap<>();
                    for (Object filter : Json.array(sub, "filterBy")) {
                        String parameter = field(filter, "filterParameter");
                        String value = field(filter, "value");
                        if (parameter != null && value != null) {
                            filters.put(parameter, value);
                        }
                    }
                    subscriptions.add(new TopicSubscription(held.id(), topic, endpoint, filters,
                            "id-only".equals(field(sub, "content"))));
                }
                return subscriptions;
            }
        };
    }

    /** A topic subscription's filters, compiled by the compiler a search uses. */
    static BiFunction<String, Map<String, String>, Criteria> filterCompiler(
            ParametersInForce inForce) {
        return (typeName, filters) -> ElementSearch.compile(inForce, typeName, filters).criteria();
    }

    /**
     * The R5 notification: a status resource naming the subscription, the
     * topic and the event, then the focus — by name always, with its content
     * only where the subscription asked for it.
     */
    static NotificationComposer composer() {
        return (subscription, topic, item, eventNumber) -> {
            String focus = item.typeName() + "/" + item.objectId();
            StringBuilder json = new StringBuilder()
                    .append("{\"resourceType\":\"Bundle\",\"type\":\"subscription-notification\",")
                    .append("\"entry\":[{\"resource\":{\"resourceType\":\"SubscriptionStatus\",")
                    .append("\"status\":\"active\",\"type\":\"event-notification\",")
                    .append("\"eventsSinceSubscriptionStart\":").append(eventNumber).append(',')
                    .append("\"subscription\":{\"reference\":")
                    .append(Json.quoted("Subscription/" + subscription.id())).append("},")
                    .append("\"topic\":").append(Json.quoted(topic.url())).append(',')
                    .append("\"notificationEvent\":[{\"eventNumber\":").append(eventNumber)
                    .append(",\"focus\":{\"reference\":").append(Json.quoted(focus))
                    .append("}}]}}");
            json.append(",{\"fullUrl\":").append(Json.quoted(focus));
            if (!subscription.idOnly() && item.kind() != ChangeKind.DELETED
                    && item.payload() != null) {
                json.append(",\"resource\":")
                        .append(new String(item.payload(), StandardCharsets.UTF_8));
            }
            return json.append("}]}").toString();
        };
    }

    /** Active records of one type, or none where the tenant does not serve it. */
    private static List<StoredObject> select(ObjectStore store, ParametersInForce inForce,
            String typeName) {
        try {
            return store.select(ElementSearch.compile(inForce, typeName,
                    Map.of("status", "active")).criteria());
        } catch (RuntimeException notServed) {
            return List.of();
        }
    }

    private static String field(Object node, String name) {
        Object value = ((Map<?, ?>) node).get(name);
        return value == null ? null : String.valueOf(value);
    }
}
