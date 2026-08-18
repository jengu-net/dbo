package cloud.jengu.dbo.fhir.r4;

import cloud.jengu.dbo.fhir.common.ValidationFailedException;

import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;

import cloud.jengu.dbo.core.api.feed.FeedChunk;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The FHIR-facing facade over one tenant's engine store: validated writes,
 * strict search returning searchset Bundles, conditional canonical upserts.
 * JSON in, JSON out — the personality boundary (§7.3).
 */
public final class R4Store implements cloud.jengu.dbo.fhir.common.FhirStoreFacade {

    private final ObjectStore store;
    private final R4Personality personality;
    private final String baseUrl;

    public R4Store(ObjectStore store, R4Personality personality, String baseUrl) {
        this.store = store;
        this.personality = personality;
        this.baseUrl = baseUrl;
    }

    /** Validate then create; returns after the single-transaction commit. */
    @Override
    public PutResult create(String resourceJson) {
        String type = personality.resourceTypeOf(resourceJson);
        List<String> issues = personality.validate(resourceJson);
        if (!issues.isEmpty()) {
            throw new ValidationFailedException(type, issues);
        }
        return store.put(PutRequest.create(type, resourceJson.getBytes(StandardCharsets.UTF_8)));
    }

    /** Validated update; null expectedVersion = unconditional. */
    @Override
    public PutResult update(String id, Long expectedVersion, String resourceJson) {
        String type = personality.resourceTypeOf(resourceJson);
        List<String> issues = personality.validate(resourceJson);
        if (!issues.isEmpty()) {
            throw new ValidationFailedException(type, issues);
        }
        return store.put(new PutRequest(type, id, expectedVersion,
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
    @Override
    public String search(String typeName, Map<String, String> params, String cursor) {
        R4Personality.CompiledSearch compiled = personality.compileSearch(typeName, params);

        if (compiled.byId() != null) {
            var hit = store.get(typeName, compiled.byId());
            var items = hit.map(List::of).orElse(List.of());
            return personality.toSearchBundle(
                    new FeedChunk<>(items, null, true), baseUrl, typeName, params, null, compiled.elements());
        }
        if (compiled.countOnly()) {
            return personality.countBundle(store.count(compiled.criteria()));
        }

        FeedChunk<StoredObject> chunk = store.page(compiled.criteria(), cursor);

        List<StoredObject> included = null;
        if (!compiled.includeRefParams().isEmpty()) {
            included = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (StoredObject item : chunk.items()) {
                String json = new String(item.payload(), StandardCharsets.UTF_8);
                for (String refParam : compiled.includeRefParams()) {
                    for (String[] target : personality.referencedTargets(json, refParam)) {
                        if (seen.add(target[0] + "/" + target[1])) {
                            store.get(target[0], target[1]).ifPresent(included::add);
                        }
                    }
                }
            }
        }
        return personality.toSearchBundle(chunk, baseUrl, typeName, params, included, compiled.elements());
    }

    /**
     * FHIR conditional create (If-None-Exist semantics). Per
     * REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS the condition must be the
     * type's primary identity: {@code identifier=sys|value} or {@code url=…}.
     */
    @Override
    public PutResult conditionalCreate(String resourceJson, Map<String, String> condition) {
        String type = personality.resourceTypeOf(resourceJson);
        List<String> issues = personality.validate(resourceJson);
        if (!issues.isEmpty()) {
            throw new ValidationFailedException(type, issues);
        }
        if (condition.size() != 1) {
            throw new IllegalArgumentException(
                    "conditional create requires exactly one identity condition, got: " + condition.keySet());
        }
        Map.Entry<String, String> cond = condition.entrySet().iterator().next();
        IdentityRef ref = switch (cond.getKey()) {
            case "identifier" -> {
                int pipe = cond.getValue().indexOf('|');
                if (pipe <= 0 || pipe == cond.getValue().length() - 1) {
                    throw new IllegalArgumentException(
                            "conditional identifier must be system|value: " + cond.getValue());
                }
                yield IdentityRef.identifier(cond.getValue().substring(0, pipe),
                        cond.getValue().substring(pipe + 1));
            }
            case "url" -> IdentityRef.canonical(cond.getValue());
            default -> throw new IllegalArgumentException(
                    "conditional create accepts only identity conditions (identifier=, url=), got: "
                            + cond.getKey());
        };
        return store.putIfAbsent(ref, PutRequest.create(type,
                resourceJson.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String read(String typeName, String id) {
        // Rendered, not served raw: the payload is the truth and
        // carries no id, so a direct read would hand back a resource the
        // client cannot reference — while the same object in a search hit
        // has one.
        return store.get(typeName, id)
                .map(personality::toResourceJson)
                .orElse(null);
    }

    @Override
    public ReadResult readForServing(String typeName, String id) {
        return store.get(typeName, id)
                .map(o -> new ReadResult(personality.toResourceJson(o),
                        o.versionId(), o.lastUpdated()))
                .orElse(null);
    }


    @Override
    public void delete(String typeName, String id, Long expectedVersion) {
        store.delete(typeName, id, expectedVersion);
    }

    @Override
    public String historyBundle(String typeName, String id) {
        return personality.toHistoryBundle(store.history(typeName, id), baseUrl, typeName);
    }

    @Override
    public String capabilityStatement(String base) {
        return personality.capabilityStatement(base);
    }

    @Override
    public String operationOutcome(String issueCode, String diagnostics) {
        return personality.operationOutcome(issueCode, diagnostics);
    }

    @Override
    public boolean knowsType(String typeName) {
        return personality.knowsType(typeName);
    }

}
