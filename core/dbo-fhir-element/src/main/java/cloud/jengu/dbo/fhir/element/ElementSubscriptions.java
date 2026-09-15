package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.subscriptions.SubscriptionSource;
import cloud.jengu.dbo.subscriptions.SubscriptionSpec;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static String field(Object node, String name) {
        Object value = ((Map<?, ?>) node).get(name);
        return value == null ? null : String.valueOf(value);
    }
}
