package cloud.jengu.dbo.scim;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.VersionConflictException;
import cloud.jengu.dbo.pdi.PersonVault;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tenant's SCIM 2.0 surface (RFC 7644): the authority's provisioning
 * door, mounted beside its OIDC endpoints when the tenant spec declares it
 * (REQ-DBO-SCIM-DECLARED-PER-TENANT).
 *
 * <p>Admission is the {@link Scopes#SCIM} scope on this tenant's own
 * authority — a directory credential, structurally blind to the store's
 * resource surface (REQ-DBO-SCIM-DIRECTORY-CREDENTIAL). Every operation
 * runs with the client as caller and an administrative purpose stated, so
 * identifying data leaves as ONE recorded SCIM disclosure rather than a
 * series of resource reads a reviewer must reassemble into intent
 * (REQ-DBO-SCIM-EVERY-OP-IS-A-DISCLOSURE).
 */
public final class ScimHandler implements HttpHandler {

    /** HL7 PurposeOfUse for administrative operations — what a SCIM call is. */
    static final String PURPOSE = "SYSADMIN";

    private static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    private static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    private final TenantAuthority authority;
    private final ScimUsers users;
    private final String basePath;

    public ScimHandler(TenantAuthority authority, ObjectStore engine, PersonVault vault,
            String system, String basePath) {
        this.authority = authority;
        this.users = new ScimUsers(engine, vault, system);
        this.basePath = basePath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                error(exchange, 401, "authentication required");
                return;
            }
            Optional<TenantAuthority.AuthContext> context =
                    authority.validate(header.substring(7).trim());
            if (context.isEmpty()) {
                error(exchange, 401, "invalid token");
                return;
            }
            if (!context.get().scopes().contains(Scopes.SCIM)) {
                // A store credential is not a directory credential — the
                // refusal is symmetric with the FHIR surface refusing SCIM's.
                error(exchange, 403, "insufficient scope: provisioning needs '"
                        + Scopes.SCIM + "'");
                return;
            }
            Caller.set(context.get().clientId());
            Disclosure.set(Disclosure.Mode.INCLUDE, PURPOSE);
            route(exchange);
        } catch (IdentityConflictException conflict) {
            error(exchange, 409, conflict.getMessage());
        } catch (VersionConflictException stale) {
            error(exchange, 412, stale.getMessage());
        } catch (IllegalArgumentException bad) {
            error(exchange, 400, bad.getMessage());
        } catch (RuntimeException failed) {
            error(exchange, 500, failed.getMessage() == null
                    ? failed.getClass().getSimpleName() : failed.getMessage());
        } finally {
            Disclosure.clear();
            Caller.clear();
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring(basePath.length());
        String method = exchange.getRequestMethod();
        switch (path) {
            case "/ServiceProviderConfig" -> respond(exchange, 200, serviceProviderConfig());
            case "/Schemas" -> respond(exchange, 200, wrapped(List.of(userSchema())));
            case "/ResourceTypes" -> respond(exchange, 200, wrapped(List.of(userResourceType())));
            case "/Users" -> {
                switch (method) {
                    case "GET" -> listUsers(exchange);
                    case "POST" -> {
                        Map<String, Object> created = users.create(body(exchange));
                        respond(exchange, 201, created);
                    }
                    default -> error(exchange, 405, method + " is not a User operation here");
                }
            }
            case "/Groups" -> {
                if ("GET".equals(method)) {
                    respond(exchange, 200, wrapped(authority.activeRoleCodes().stream()
                            .map(ScimHandler::group).toList()));
                } else {
                    // Who works here is the IdP's call; who is an admin here
                    // is not. The door stays shut, permanently.
                    error(exchange, 405, "Groups are read-only: role governance "
                            + "does not arrive by provisioning");
                }
            }
            default -> {
                if (path.startsWith("/Users/")) {
                    userById(exchange, method, path.substring("/Users/".length()));
                } else if (path.startsWith("/Groups/")) {
                    if ("GET".equals(method)) {
                        respond(exchange, 200, group(path.substring("/Groups/".length())));
                    } else {
                        error(exchange, 405, "Groups are read-only: role governance "
                                + "does not arrive by provisioning");
                    }
                } else {
                    error(exchange, 404, "no such SCIM resource: " + path);
                }
            }
        }
    }

    private void listUsers(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        String filter = query.get("filter");
        List<Map<String, Object>> matched;
        if (filter != null) {
            matched = filtered(filter);
        } else {
            int startIndex = intOf(query.get("startIndex"), 1);
            int count = intOf(query.get("count"), 100);
            matched = users.list(startIndex, count);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("schemas", List.of(LIST_SCHEMA));
            response.put("totalResults", users.total());
            response.put("startIndex", startIndex);
            response.put("itemsPerPage", matched.size());
            response.put("Resources", matched);
            respond(exchange, 200, response);
            return;
        }
        respond(exchange, 200, wrapped(matched));
    }

    /**
     * The two filters an identity provider actually sends —
     * {@code externalId eq "…"} and {@code userName eq "…"} — both answered
     * by exact vault lookup. Anything else is refused rather than
     * half-answered.
     */
    private List<Map<String, Object>> filtered(String filter) {
        String[] parts = filter.trim().split("\\s+", 3);
        if (parts.length != 3 || !"eq".equalsIgnoreCase(parts[1])
                || parts[2].length() < 2 || !parts[2].startsWith("\"")
                || !parts[2].endsWith("\"")) {
            throw new IllegalArgumentException("unsupported filter — this surface answers "
                    + "externalId eq \"…\" and userName eq \"…\", exactly");
        }
        String value = parts[2].substring(1, parts[2].length() - 1);
        return switch (parts[0]) {
            case "externalId" -> users.byExternalId(value).map(List::of).orElse(List.of());
            case "userName" -> users.byUserName(value);
            default -> throw new IllegalArgumentException("unsupported filter attribute '"
                    + parts[0] + "' — this surface answers externalId and userName, exactly");
        };
    }

    private void userById(HttpExchange exchange, String method, String id) throws IOException {
        switch (method) {
            case "GET" -> {
                Optional<Map<String, Object>> user = users.read(id);
                if (user.isEmpty()) {
                    error(exchange, 404, "no User " + id);
                } else {
                    respond(exchange, 200, user.get());
                }
            }
            case "PUT" -> {
                Long expected = expectedVersion(exchange);
                Optional<Map<String, Object>> replaced =
                        users.replace(id, body(exchange), expected);
                if (replaced.isEmpty()) {
                    error(exchange, 404, "no User " + id);
                } else {
                    respond(exchange, 200, replaced.get());
                }
            }
            // Deprovision is a state, not an erasure: erasure is the vault's
            // own ceremony with a different meaning and a different audit
            // shape, and it does not arrive over a provisioning wire.
            case "DELETE" -> error(exchange, 405,
                    "deprovision by replacing with active=false; erasure is not a "
                            + "provisioning operation");
            default -> error(exchange, 405, method + " is not a User operation here");
        }
    }

    // ------------------------------------------------------------ plumbing

    private static Long expectedVersion(HttpExchange exchange) {
        String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
        if (ifMatch == null) {
            return null;
        }
        String version = ifMatch.replace("W/", "").replace("\"", "").trim();
        try {
            return Long.parseLong(version);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match is not a version: " + ifMatch);
        }
    }

    private static Object body(HttpExchange exchange) throws IOException {
        return Json.parse(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(java.net.URLDecoder.decode(pair.substring(0, eq),
                                    StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(pair.substring(eq + 1),
                                    StandardCharsets.UTF_8));
                }
            }
        }
        return params;
    }

    private static int intOf(String value, int absent) {
        return value == null ? absent : Integer.parseInt(value);
    }

    private static Map<String, Object> wrapped(List<? extends Object> resources) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemas", List.of(LIST_SCHEMA));
        response.put("totalResults", resources.size());
        response.put("Resources", resources);
        return response;
    }

    private static Map<String, Object> group(String roleCode) {
        return Map.of(
                "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:Group"),
                "id", roleCode,
                "displayName", roleCode);
    }

    private static Map<String, Object> serviceProviderConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schemas",
                List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
        config.put("patch", Map.of("supported", false));
        config.put("bulk", Map.of("supported", false));
        config.put("filter", Map.of("supported", true, "maxResults", 200));
        config.put("changePassword", Map.of("supported", false));
        config.put("sort", Map.of("supported", false));
        config.put("etag", Map.of("supported", true));
        config.put("authenticationSchemes", List.of(Map.of(
                "type", "oauthbearertoken",
                "name", "OAuth Bearer Token",
                "description", "client_credentials on this tenant's own authority, scope '"
                        + Scopes.SCIM + "'")));
        return config;
    }

    private static Map<String, Object> userSchema() {
        return Map.of(
                "id", ScimUsers.USER_SCHEMA,
                "name", "User",
                "description", "A provisioned staff member: the person, with a linked "
                        + "practitioner capacity");
    }

    private static Map<String, Object> userResourceType() {
        return Map.of(
                "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"),
                "id", "User",
                "name", "User",
                "endpoint", "/Users",
                "schema", ScimUsers.USER_SCHEMA);
    }

    private static void respond(HttpExchange exchange, int status, Object payload)
            throws IOException {
        byte[] body = Json.render(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void error(HttpExchange exchange, int status, String detail)
            throws IOException {
        respond(exchange, status, Map.of(
                "schemas", List.of(ERROR_SCHEMA),
                "status", String.valueOf(status),
                "detail", detail == null ? "" : detail));
    }
}
