package cloud.jengu.dbo.fhir.r4;

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

}
