package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClaims;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.sync.ContentSyncEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Letting a local override go at a place, so the origin's version applies.
 *
 * <p>A local record standing in front of a version the origin sends parks
 * that version, and every later one, until the record is gone. Where the
 * store can tell the record is not a person's — its configuration's account
 * names it, or its type is one only the configuration writes — it lets go by
 * itself. Where it cannot, a person at the place can: they know whether
 * anybody there wrote it. This is where they say so.
 *
 * <p><b>Said before it is done.</b> {@code preview} lists each record that
 * would go, by type and what it is called, beside what the origin holds
 * instead, so the decision is made on the list rather than on a count; the
 * door's own path does the same with the same body and lets them go.
 *
 * <p><b>An ordinary write.</b> Each record is deleted through the tenant's
 * store under whoever asked, so the trail names them as it names any other
 * delete, and the tenant's write discipline applies as it does to any other.
 * Then the place's streams reconcile, which applies what they had parked.
 *
 * <p>Behind the configuration scope: what a place holds in front of its origin
 * is part of what the tenant is there, not a record somebody may write into.
 */
final class OverridesHandler implements HttpHandler {

    static final String SCOPE = cloud.jengu.dbo.auth.Scopes.CONFIGURATION;

    private final TenantAuthority authority;
    private final ObjectStore store;
    private final BooleanSupplier place;
    private final Supplier<List<ContentSyncEngine>> streams;

    /**
     * @param store   the tenant's store as a caller writes to it, so what is let
     *                go is recorded and disciplined as any delete is
     * @param place   whether the tenant is a place now; asked per request, since
     *                a tenant becomes one after its door is mounted
     * @param streams the streams the place reads its origin through
     */
    OverridesHandler(TenantAuthority authority, ObjectStore store, BooleanSupplier place,
            Supplier<List<ContentSyncEngine>> streams) {
        this.authority = authority;
        this.store = store;
        this.place = place;
        this.streams = streams;
    }

    /** One local record standing in front of the origin's version, and that version. */
    private record Shadow(ContentSyncEngine stream, String type, StoredObject local,
            ContentSyncEngine.ShadowedEvent upstream) {
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                fail(exchange, 405, "invalid_request", "overrides are listed and let go with POST");
                return;
            }
            TenantAuthority.AuthContext asking = permitted(exchange);
            if (asking == null) {
                return;
            }
            String verb = exchange.getRequestURI().getPath()
                    .substring(exchange.getHttpContext().getPath().length());
            if (verb.startsWith("/")) {
                verb = verb.substring(1);
            }
            if (!verb.isEmpty() && !"preview".equals(verb)) {
                fail(exchange, 404, "invalid_request", "this door lets local overrides go, and "
                        + "'preview' says which it would; it was asked for '" + verb + "'");
                return;
            }
            if (!place.getAsBoolean()) {
                fail(exchange, 409, "not_a_place", "this tenant is not a place of another, so "
                        + "nothing it holds stands in front of an origin's version");
                return;
            }
            Object read = RecordWire.read(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            if (!(read instanceof Map<?, ?> asked) || !(asked.get("dependency") instanceof String
                    dependency) || !(asked.get("type") instanceof String type)) {
                fail(exchange, 400, "invalid_request", "name the dependency and the type, and "
                        + "the records if not every one: {\"dependency\": \"...\", \"type\": "
                        + "\"PlanDefinition\", \"records\": [\"<id>\"]}");
                return;
            }
            Set<String> named = new LinkedHashSet<>();
            if (asked.get("records") instanceof List<?> records) {
                records.forEach(one -> named.add(String.valueOf(one)));
            }
            List<Shadow> overrides = overrides(dependency, type);
            if (!named.isEmpty()) {
                Set<String> shadowing = new LinkedHashSet<>();
                overrides.forEach(one -> shadowing.add(one.local().id()));
                List<String> nothing = named.stream().filter(id -> !shadowing.contains(id))
                        .toList();
                if (!nothing.isEmpty()) {
                    fail(exchange, 422, "shadows_nothing", "these records hold back no version "
                            + "of '" + dependency + "', so there is nothing to let go: "
                            + String.join(", ", nothing));
                    return;
                }
                overrides = overrides.stream().filter(one -> named.contains(one.local().id()))
                        .toList();
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("dependency", dependency);
            answer.put("type", type);
            if ("preview".equals(verb)) {
                answer.put("preview", true);
                answer.put("overrides", said(overrides));
                respond(exchange, 200, answer);
                return;
            }
            letGo(exchange, asking, overrides, answer);
        } catch (RuntimeException failed) {
            fail(exchange, 500, "let_go_failed", "letting the overrides go did not complete");
        } finally {
            exchange.close();
        }
    }

    private void letGo(HttpExchange exchange, TenantAuthority.AuthContext asking,
            List<Shadow> overrides, Map<String, Object> answer) throws IOException {
        List<Shadow> released = new ArrayList<>();
        List<Object> kept = new ArrayList<>();
        Set<ContentSyncEngine> touched = new LinkedHashSet<>();
        String outer = cloud.jengu.dbo.core.api.Caller.current();
        String outerFor = cloud.jengu.dbo.core.api.Caller.onBehalfOf();
        if (asking.fhirUser() == null) {
            cloud.jengu.dbo.core.api.Caller.set(asking.clientId());
        } else {
            cloud.jengu.dbo.core.api.Caller.setChain(asking.clientId(), asking.fhirUser());
        }
        try {
            for (Shadow one : overrides) {
                try {
                    store.delete(one.type(), one.local().id(), one.local().versionId());
                    released.add(one);
                    touched.add(one.stream());
                } catch (RuntimeException refused) {
                    kept.add(Map.of("record", one.local().id(),
                            "reason", String.valueOf(refused.getMessage())));
                }
            }
        } finally {
            if (outerFor == null) {
                cloud.jengu.dbo.core.api.Caller.set(outer);
            } else {
                cloud.jengu.dbo.core.api.Caller.setChain(outer, outerFor);
            }
        }
        long applied = 0;
        for (ContentSyncEngine stream : touched) {
            applied += stream.reconcile();
        }
        answer.put("released", said(released));
        answer.put("applied", applied);
        // Always present, empty included, so a caller does not learn to read it
        // by its absence.
        answer.put("kept", kept);
        respond(exchange, 200, answer);
    }

    /** Every local record of this type holding back a version of this dependency. */
    private List<Shadow> overrides(String dependency, String type) {
        List<Shadow> found = new ArrayList<>();
        for (ContentSyncEngine stream : streams.get()) {
            if (!dependency.equals(stream.dependency())) {
                continue;
            }
            for (ContentSyncEngine.ShadowedEvent upstream : stream.shadowedEvents()) {
                if (!type.equals(upstream.typeName()) || upstream.shadowedBy() == null) {
                    continue;
                }
                Optional<StoredObject> local = store.get(type, upstream.shadowedBy());
                local.ifPresent(record -> found.add(new Shadow(stream, type, record, upstream)));
            }
        }
        return found;
    }

    private List<Object> said(List<Shadow> overrides) {
        List<Object> said = new ArrayList<>();
        for (Shadow one : overrides) {
            Map<String, Object> origin = new LinkedHashMap<>();
            origin.put("record", one.upstream().objectId());
            origin.put("version", one.upstream().sourceVersionId());
            origin.put("calledBy", calledBy(one.type(), one.upstream().payload()));
            Map<String, Object> override = new LinkedHashMap<>();
            override.put("record", one.local().id());
            override.put("version", one.local().versionId());
            override.put("calledBy", calledBy(one.type(), one.local().payload()));
            override.put("origin", origin);
            said.add(override);
        }
        return said;
    }

    private List<Object> calledBy(String type, byte[] payload) {
        List<Object> names = new ArrayList<>();
        for (Identifier identity : IdentityClaims.named(store, type, payload)) {
            names.add(Map.of("system", identity.system(), "value", identity.value()));
        }
        return names;
    }

    private TenantAuthority.AuthContext permitted(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        Optional<TenantAuthority.AuthContext> context =
                bearer == null ? Optional.empty() : authority.validate(bearer);
        if (context.isEmpty() || !context.get().scopes().contains(SCOPE)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, context.isEmpty() ? 401 : 403, "access_denied",
                    "letting an override go needs the '" + SCOPE
                            + "' scope, which nothing else grants");
            return null;
        }
        return context.get();
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] bytes = RecordWire.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void fail(HttpExchange exchange, int status, String error, String detail)
            throws IOException {
        respond(exchange, status, Map.of("error", error, "detail", detail));
    }
}
