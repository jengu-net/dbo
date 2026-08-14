package cloud.jengu.dbo.fhir.r4;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The FHIR-facing facade over one tenant's engine store: validated writes,
 * strict search returning searchset Bundles, conditional canonical upserts.
 * JSON in, JSON out — the personality boundary (§7.3).
 */
public final class R4Store {

    private final ObjectStore store;
    private final R4Personality personality;
    private final String baseUrl;

    public R4Store(ObjectStore store, R4Personality personality, String baseUrl) {
        this.store = store;
        this.personality = personality;
        this.baseUrl = baseUrl;
    }

    /** Validate then create; returns after the single-transaction commit. */
    public PutResult create(String resourceJson) {
        String type = personality.resourceTypeOf(resourceJson);
        List<String> issues = personality.validate(resourceJson);
        if (!issues.isEmpty()) {
            throw new ValidationFailedException(type, issues);
        }
        return store.put(PutRequest.create(type, resourceJson.getBytes(StandardCharsets.UTF_8)));
    }

    /** Validated update with optimistic version check. */
    public PutResult update(String id, long expectedVersion, String resourceJson) {
        String type = personality.resourceTypeOf(resourceJson);
        List<String> issues = personality.validate(resourceJson);
        if (!issues.isEmpty()) {
            throw new ValidationFailedException(type, issues);
        }
        return store.put(PutRequest.update(type, id, expectedVersion,
                resourceJson.getBytes(StandardCharsets.UTF_8)));
    }

    /** Conditional upsert of a canonical artifact by its url (validated). */
    public PutResult putCanonical(String resourceJson) {
        String type = personality.resourceTypeOf(resourceJson);
        List<String> issues = personality.validate(resourceJson);
        if (!issues.isEmpty()) {
            throw new ValidationFailedException(type, issues);
        }
        String url = personality.canonicalUrlOf(resourceJson);
        return store.putConditional(IdentityRef.canonical(url),
                PutRequest.create(type, resourceJson.getBytes(StandardCharsets.UTF_8)));
    }

    /** Strict FHIR search over one type; returns a searchset Bundle with link[next]. */
    public String search(String typeName, Map<String, String> params, String cursor) {
        Criteria criteria = personality.compileSearch(typeName, params);
        FeedChunk<StoredObject> chunk = store.page(criteria, cursor);
        return personality.toSearchBundle(chunk, baseUrl, typeName, params);
    }

    public String read(String typeName, String id) {
        return store.get(typeName, id)
                .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                .orElse(null);
    }

}
