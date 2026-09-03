package cloud.jengu.dbo.fleet;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Scope;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reads a deployment from outside every container, over the doors its nodes
 * and tenants already serve.
 *
 * <p>Two kinds of question, two kinds of credential, and the split is the
 * design. A <em>node</em> is asked what tenants it is serving and what steps
 * it has installed, under the deployment's own token, because both answers
 * name other tenants' existence and no tenant credential may buy that. A
 * <em>tenant</em> is asked about its work under a credential minted by that
 * tenant's own authority, so the tenant refuses for itself and the reader
 * is never handed a surface that crosses tenants.
 *
 * <p><b>It queries and never copies.</b> Every answer is the store's own,
 * rendered here as it arrived and labelled with the node it came from. A
 * cached mirror would be a second answer to a question the tenant store
 * already answers authoritatively, and the two would disagree exactly when it
 * mattered.
 *
 * <p><b>A node that does not answer is a fact in the reading, not an
 * exception out of it.</b> Each ask is bounded by a timeout, and the outcome
 * is recorded per node and per tenant, so one dead node costs the reader a
 * timeout and the operator a line, never the rest of the fleet.
 */
public final class FleetReader {

    /** The filter for the run question; every field absent means any. */
    public record RunFilter(String process, String step, String holder, Integer limit) {

        public static final RunFilter ANY = new RunFilter(null, null, null, null);

        /** The ones somebody has to look at. */
        public static RunFilter heldByAPerson() {
            return new RunFilter(null, null, "person", null);
        }

        Map<String, Object> asBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            if (process != null) {
                body.put("process", process);
            }
            if (step != null) {
                body.put("step", step);
            }
            if (holder != null) {
                body.put("holder", holder);
            }
            if (limit != null) {
                body.put("limit", limit);
            }
            return body;
        }
    }

    /** What one act came to — where it was tried, how it ended, and why not. */
    public record Acted(String node, Reading.Outcome outcome, String detail) {}

    private final List<Node> nodes;
    private final Credentials credentials;
    private final Credentials supervisory;
    private final HttpClient http;
    private final Duration timeout;

    /** A reader that looks and does not touch: it holds no supervisory credential. */
    public FleetReader(List<Node> nodes, Credentials credentials) {
        this(nodes, credentials, Credentials.none(), Duration.ofSeconds(10));
    }

    public FleetReader(List<Node> nodes, Credentials credentials, Duration timeout) {
        this(nodes, credentials, Credentials.none(), timeout);
    }

    /**
     * @param credentials what the reader asks a tenant about its work with —
     *                    the fleet scope, which reads envelopes and nothing
     *                    below them
     * @param supervisory what it acts with, held separately and usually not
     *                    held at all. Looking is far more common than acting
     *                    and must not require the authority to overturn
     *                    somebody's work, so the two are different
     *                    credentials rather than two uses of one — and a
     *                    reader given no supervisory secret is a read-only
     *                    tool by construction rather than by discipline.
     * @param timeout     the bound on every single ask. A fleet read that
     *                    could hang on one node would make the slowest node
     *                    the reader's availability.
     */
    public FleetReader(List<Node> nodes, Credentials credentials, Credentials supervisory,
            Duration timeout) {
        this.nodes = List.copyOf(nodes);
        this.credentials = credentials;
        this.supervisory = supervisory;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Everything, with the runs filtered as asked. */
    public Reading read(RunFilter filter) {
        List<Reading.NodeReading> readings = new ArrayList<>();
        for (Node node : nodes) {
            readings.add(readNode(node, filter));
        }
        return new Reading(readings, networkMap(readings));
    }

    /**
     * Makes one closed run claimable again, through the tenant's lane.
     *
     * <p><b>Through the lane, never around it.</b> The reader holds an
     * identity and an entitlement and posts the same verb a participant
     * posts; every rule about the act — that the credential covers the step,
     * that the step declares the action, that the run belongs to this
     * appliance — is the store's and is met on the way in. What comes back is
     * the tenant's own refusal or its assent.
     *
     * <p>Only the run's key travels. The reader holds envelopes, not runs,
     * and the lane reads the store's own copy of what it is acting on.
     *
     * <p>The first node serving the tenant is asked. A tenant's work lives in
     * the tenant's database, so every node serving it reaches the same runs,
     * and which one carries the ask is not a decision worth making.
     */
    public Acted reopen(String tenant, String runKey, String because) {
        Optional<Credentials.Credential> credential = supervisory.forTenant(tenant);
        if (credential.isEmpty()) {
            return new Acted(null, Reading.Outcome.NO_CREDENTIAL,
                    "this reader holds no supervisory credential for '" + tenant + "', so it "
                            + "did not ask. Looking and acting are separately granted");
        }
        for (Node node : nodes) {
            Answer tenants = get(node.base().resolve("/runtime/tenants"), node.opsToken());
            if (tenants.outcome() != Reading.Outcome.ANSWERED) {
                continue;
            }
            boolean serving = rows(tenants.body(), "tenants").stream()
                    .anyMatch(state -> tenant.equals(state.get("code"))
                            && "serving".equals(state.get("state")));
            if (!serving) {
                continue;
            }
            Answer token = mint(node, tenant, credential.get());
            if (token.outcome() != Reading.Outcome.ANSWERED) {
                return new Acted(node.name(), token.outcome(), token.detail());
            }
            String bearer = token.body();
            Lane lane = HttpLane.to(node.base().resolve("/t/" + tenant + "/work"),
                    () -> bearer, tenant, credential.get().clientId(),
                    new Executor(credential.get().clientId(), "1.0",
                            "cloud.jengu.dbo.fleet", Scope.BASELINE));
            try {
                lane.reopen(Run.named(runKey), because);
                return new Acted(node.name(), Reading.Outcome.ANSWERED, null);
            } catch (RuntimeException refused) {
                // The tenant's own words. A refusal here is settled — the
                // credential does not cover the step, the step declares no
                // reopening, the run is another appliance's — and telling an
                // operator which is the whole value of asking.
                return new Acted(node.name(), Reading.Outcome.REFUSED,
                        String.valueOf(refused.getMessage()));
            }
        }
        return new Acted(null, Reading.Outcome.NOT_SERVING,
                "no node in this fleet reports serving '" + tenant + "'");
    }

    private Reading.NodeReading readNode(Node node, RunFilter filter) {
        Answer tenants = get(node.base().resolve("/runtime/tenants"), node.opsToken());
        if (tenants.outcome() != Reading.Outcome.ANSWERED) {
            return new Reading.NodeReading(node.name(), tenants.outcome(), tenants.detail(),
                    List.of(), List.of());
        }
        Answer catalogue = get(node.base().resolve("/runtime/catalogue"), node.opsToken());
        List<Reading.TenantReading> perTenant = new ArrayList<>();
        for (Map<String, Object> state : rows(tenants.body(), "tenants")) {
            perTenant.add(readTenant(node, String.valueOf(state.get("code")),
                    String.valueOf(state.get("state")), filter));
        }
        return new Reading.NodeReading(node.name(), Reading.Outcome.ANSWERED, null, perTenant,
                catalogue.outcome() == Reading.Outcome.ANSWERED
                        ? rows(catalogue.body(), "steps") : List.of());
    }

    private Reading.TenantReading readTenant(Node node, String code, String state,
            RunFilter filter) {
        if (!"serving".equals(state)) {
            return new Reading.TenantReading(code, state, Reading.Outcome.NOT_SERVING,
                    "the node is not serving this tenant, so there is nobody to ask", List.of());
        }
        Optional<Credentials.Credential> credential = credentials.forTenant(code);
        if (credential.isEmpty()) {
            return new Reading.TenantReading(code, state, Reading.Outcome.NO_CREDENTIAL,
                    "the reader holds no credential for this tenant, so it did not ask",
                    List.of());
        }
        Answer token = mint(node, code, credential.get());
        if (token.outcome() != Reading.Outcome.ANSWERED) {
            return new Reading.TenantReading(code, state, token.outcome(), token.detail(),
                    List.of());
        }
        Answer runs = post(node.base().resolve("/t/" + code + "/fleet/runs"), token.body(),
                RecordWire.write(filter.asBody()));
        if (runs.outcome() != Reading.Outcome.ANSWERED) {
            return new Reading.TenantReading(code, state, runs.outcome(), runs.detail(),
                    List.of());
        }
        return new Reading.TenantReading(code, state, Reading.Outcome.ANSWERED, null,
                rows(runs.body(), "runs"));
    }

    /**
     * The union of every node's inventory: step, version, nodes. Two nodes
     * carrying one step at two versions are two rows under it, which is the
     * answer a rolling upgrade wants and a flat set could not give.
     */
    private static Map<String, Map<String, List<String>>> networkMap(
            List<Reading.NodeReading> readings) {
        Map<String, Map<String, List<String>>> map = new TreeMap<>();
        for (Reading.NodeReading node : readings) {
            for (Map<String, Object> step : node.catalogue()) {
                map.computeIfAbsent(String.valueOf(step.get("id")), id -> new TreeMap<>())
                        .computeIfAbsent(String.valueOf(step.get("version")),
                                version -> new ArrayList<>())
                        .add(node.node());
            }
        }
        return map;
    }

    // ── the wire ──────────────────────────────────────────────────────────

    /** One ask, as it ended: an answer with a body, or an outcome with a reason. */
    private record Answer(Reading.Outcome outcome, String detail, String body) {
        static Answer answered(String body) {
            return new Answer(Reading.Outcome.ANSWERED, null, body);
        }
    }

    private Answer mint(Node node, String code, Credentials.Credential credential) {
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(credential.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(credential.secret(), StandardCharsets.UTF_8);
        Answer issued = send(HttpRequest.newBuilder(node.base().resolve("/t/" + code + "/oidc/token"))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        if (issued.outcome() != Reading.Outcome.ANSWERED) {
            return issued;
        }
        Object token = fields(issued.body()).get("access_token");
        return token == null
                ? new Answer(Reading.Outcome.REFUSED, "the authority issued no token", null)
                : Answer.answered(String.valueOf(token));
    }

    private Answer get(URI uri, String bearer) {
        return send(HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Authorization", "Bearer " + bearer).GET().build());
    }

    private Answer post(URI uri, String bearer, String body) {
        return send(HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private Answer send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Answer.answered(response.body());
            }
            return new Answer(Reading.Outcome.REFUSED,
                    "HTTP " + response.statusCode() + " from " + request.uri().getPath(), null);
        } catch (IOException e) {
            return new Answer(Reading.Outcome.UNREACHABLE,
                    request.uri().getHost() + ":" + request.uri().getPort() + " — "
                            + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(Reading.Outcome.UNREACHABLE, "interrupted", null);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(String json) {
        Object read = RecordWire.read(json);
        return read instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(String json, String field) {
        Object rows = fields(json).get(field);
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> row) {
                    out.add((Map<String, Object>) row);
                }
            }
        }
        return out;
    }
}
