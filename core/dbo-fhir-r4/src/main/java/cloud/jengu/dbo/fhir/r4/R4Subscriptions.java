package cloud.jengu.dbo.fhir.r4;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.subscriptions.SubscriptionSource;
import cloud.jengu.dbo.subscriptions.SubscriptionSpec;

import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.subscriptions.NotificationComposer;
import cloud.jengu.dbo.subscriptions.TopicSpec;
import cloud.jengu.dbo.subscriptions.TopicSubscription;
import cloud.jengu.dbo.subscriptions.TopicSubscriptionSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * R4 wiring for the subscription engine: active rest-hook Subscription
 * resources become {@link SubscriptionSpec}s, and R4 criteria strings
 * ({@code Observation?code=…}) compile through the personality's strict
 * search compiler. Public surface stays HAPI-free (§7.3).
 */
public final class R4Subscriptions {

    private R4Subscriptions() {}

    /** Active rest-hook subscriptions from the store. */
    public static SubscriptionSource source(ObjectStore store, R4Personality personality) {
        return () -> {
            Criteria active = personality
                    .compileSearch("Subscription", Map.of("status", "active")).criteria();
            List<SubscriptionSpec> specs = new ArrayList<>();
            for (StoredObject o : store.select(active)) {
                var sub = (org.hl7.fhir.r4.model.Subscription) personality.ctxInternal()
                        .newJsonParser()
                        .parseResource(new String(o.payload(), StandardCharsets.UTF_8));
                boolean restHook = sub.getChannel().getType()
                        == org.hl7.fhir.r4.model.Subscription.SubscriptionChannelType.RESTHOOK;
                if (restHook && sub.hasCriteria() && sub.getChannel().hasEndpoint()) {
                    specs.add(new SubscriptionSpec(o.id(), sub.getCriteria(),
                            sub.getChannel().getEndpoint()));
                }
            }
            return specs;
        };
    }

    /** {@code Type?name=value&…} → engine criteria, strictly compiled. */
    public static Function<String, Criteria> criteriaCompiler(R4Personality personality) {
        return criteriaString -> {
            int q = criteriaString.indexOf('?');
            String type = q < 0 ? criteriaString : criteriaString.substring(0, q);
            Map<String, String> params = new LinkedHashMap<>();
            if (q >= 0 && q < criteriaString.length() - 1) {
                for (String pair : criteriaString.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException("invalid criteria pair: " + pair);
                    }
                    params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            return personality.compileSearch(type, params).criteria();
        };
    }


    /** The R4 subscriptions-backport filter extension. */
    public static final String BACKPORT_FILTER_EXT =
            "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-filter-criteria";

    /**
     * R4 backport: topics are platform CONFIGURATION (R4 has no
     * SubscriptionTopic resource); an R4 Subscription participates with
     * criteria = topic url and filters in the backport extension.
     */
    public static TopicSubscriptionSource backportTopicSource(ObjectStore store,
            R4Personality personality, List<TopicSpec> configuredTopics) {
        return new TopicSubscriptionSource() {
            @Override
            public List<TopicSpec> topics() {
                return configuredTopics;
            }

            @Override
            public List<TopicSubscription> activeTopicSubscriptions() {
                java.util.Set<String> topicUrls = new java.util.HashSet<>();
                configuredTopics.forEach(t -> topicUrls.add(t.url()));
                Criteria active = personality
                        .compileSearch("Subscription", Map.of("status", "active")).criteria();
                List<TopicSubscription> out = new ArrayList<>();
                for (StoredObject o : store.select(active)) {
                    var sub = (org.hl7.fhir.r4.model.Subscription) personality.ctxInternal()
                            .newJsonParser()
                            .parseResource(new String(o.payload(), StandardCharsets.UTF_8));
                    boolean restHook = sub.getChannel().getType()
                            == org.hl7.fhir.r4.model.Subscription.SubscriptionChannelType.RESTHOOK;
                    if (!restHook || !topicUrls.contains(sub.getCriteria())
                            || !sub.getChannel().hasEndpoint()) {
                        continue;
                    }
                    Map<String, String> filters = new LinkedHashMap<>();
                    for (var ext : sub.getExtensionsByUrl(BACKPORT_FILTER_EXT)) {
                        String criteria = ext.getValue().primitiveValue();
                        int eq = criteria.indexOf('=');
                        if (eq > 0) {
                            filters.put(criteria.substring(0, eq), criteria.substring(eq + 1));
                        }
                    }
                    boolean idOnly = !sub.getChannel().hasPayload();
                    out.add(new TopicSubscription(o.id(), sub.getCriteria(),
                            sub.getChannel().getEndpoint(), filters, idOnly));
                }
                return out;
            }
        };
    }

    /** Backport notification shape: history Bundle with a Parameters status + focus. */
    public static NotificationComposer backportComposer(R4Personality personality) {
        return (subscription, topic, item, eventNumber) -> {
            var bundle = new org.hl7.fhir.r4.model.Bundle();
            bundle.setType(org.hl7.fhir.r4.model.Bundle.BundleType.HISTORY);

            var status = new org.hl7.fhir.r4.model.Parameters();
            status.addParameter("subscription", new org.hl7.fhir.r4.model.Reference(
                    "Subscription/" + subscription.id()));
            status.addParameter("topic",
                    new org.hl7.fhir.r4.model.CanonicalType(topic.url()));
            status.addParameter("type", new org.hl7.fhir.r4.model.CodeType("event-notification"));
            status.addParameter("events-since-subscription-start",
                    new org.hl7.fhir.r4.model.StringType(Long.toString(eventNumber)));
            String focusRef = item.typeName() + "/" + item.objectId();
            var event = status.addParameter().setName("notification-event");
            event.addPart().setName("event-number")
                    .setValue(new org.hl7.fhir.r4.model.StringType(Long.toString(eventNumber)));
            event.addPart().setName("focus")
                    .setValue(new org.hl7.fhir.r4.model.Reference(focusRef));
            bundle.addEntry().setResource(status)
                    .setFullUrl("urn:uuid:" + java.util.UUID.randomUUID());

            if (!subscription.idOnly() && item.kind() != ChangeKind.DELETED && item.payload() != null) {
                var focus = (org.hl7.fhir.r4.model.Resource) personality.ctxInternal()
                        .newJsonParser()
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
    public static java.util.function.BiFunction<String, Map<String, String>, Criteria>
            filterCompiler(R4Personality personality) {
        return (resourceType, filters) -> personality.compileSearch(resourceType, filters).criteria();
    }
}
