package cloud.jengu.dbo.karaf.commands;

import org.osgi.framework.BundleContext;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;

import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A tenant's CapabilityStatement, flattened to one row per fact.
 *
 * <p>Read over the tenant's own {@code /metadata}, which is what a client sees.
 * The store facade can also render a statement, but its single-argument form is
 * the one that does not know which operations were actually registered — the
 * two-argument form exists precisely because that list is passed rather than
 * guessed — and the security block is added at the serving edge. Reading the
 * registry would report less than the tenant actually promises.
 * {@code /metadata} is the one path the guard exempts, so this needs no token.
 */
final class Capabilities {

    /**
     * The row categories. A small closed vocabulary in the first column, so the
     * second can be a path — {@code Patient.versioning} — rather than the first
     * column carrying entity names and the second field names, which leaves
     * nowhere to put a fact that is about neither.
     */
    static final String SERVER = "server";
    static final String ENTITY = "entity";

    /** The suffix whose row summarises rather than states, and so can be opened. */
    static final String SEARCH_PARAM = "searchParam";

    record Row(String type, String name, String value) {}

    private Capabilities() {
    }

    static String baseUrl(BundleContext context, String tenant) {
        return "http://" + property(context, "dbo.tenant.http.host", "127.0.0.1")
                + ":" + property(context, "dbo.tenant.http.port", "8090")
                + "/t/" + tenant + "/fhir";
    }

    static JsonObject fetch(String baseUrl) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(baseUrl + "/metadata").toURL().openConnection();
        connection.setRequestProperty("Accept", "application/fhir+json");
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(15_000);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IllegalStateException("HTTP " + status);
            }
            try (InputStream body = connection.getInputStream();
                    JsonReader reader = Json.createReader(new StringReader(
                            new String(body.readAllBytes(), StandardCharsets.UTF_8)))) {
                return reader.readObject();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Every fact, with search parameters counted by kind or listed one per row. */
    static List<Row> rows(JsonObject statement, boolean expandSearchParams) {
        List<Row> rows = new ArrayList<>();
        for (String field : new String[] {"status", "kind", "fhirVersion", "date"}) {
            string(statement, field).ifPresent(value -> rows.add(new Row(SERVER, field, value)));
        }
        JsonArray formats = statement.getJsonArray("format");
        if (formats != null) {
            for (JsonValue format : formats) {
                rows.add(new Row(SERVER, "format", text(format)));
            }
        }
        JsonArray rests = statement.getJsonArray("rest");
        if (rests == null) {
            return rows;
        }
        for (JsonValue value : rests) {
            rest(value.asJsonObject(), rows, expandSearchParams);
        }
        return rows;
    }

    /** The parameters behind one entity's {@code searchParam} row. */
    static List<Row> searchParams(JsonObject statement, String entity) {
        List<Row> rows = new ArrayList<>();
        for (JsonValue rest : statement.getJsonArray("rest")) {
            JsonArray resources = rest.asJsonObject().getJsonArray("resource");
            if (resources == null) {
                continue;
            }
            for (JsonValue value : resources) {
                JsonObject resource = value.asJsonObject();
                if (!entity.equals(string(resource, "type").orElse(null))) {
                    continue;
                }
                JsonArray declared = resource.getJsonArray(SEARCH_PARAM);
                if (declared != null) {
                    for (JsonValue searchParam : declared) {
                        JsonObject object = searchParam.asJsonObject();
                        rows.add(new Row(ENTITY,
                                entity + "." + SEARCH_PARAM + "."
                                        + string(object, "name").orElse("?"),
                                string(object, "type").orElse("")));
                    }
                }
            }
        }
        return rows;
    }

    private static void rest(JsonObject rest, List<Row> rows, boolean expandSearchParams) {
        string(rest, "mode").ifPresent(mode -> rows.add(new Row(SERVER, "rest.mode", mode)));

        JsonObject security = rest.getJsonObject("security");
        if (security != null) {
            JsonArray services = security.getJsonArray("service");
            if (services != null) {
                for (JsonValue service : services) {
                    JsonArray codings = service.asJsonObject().getJsonArray("coding");
                    if (codings != null) {
                        for (JsonValue coding : codings) {
                            string(coding.asJsonObject(), "code").ifPresent(
                                    code -> rows.add(new Row(SERVER, "security.service", code)));
                        }
                    }
                }
            }
            string(security, "description").ifPresent(description ->
                    rows.add(new Row(SERVER, "security.description", description)));
        }

        JsonArray resources = rest.getJsonArray("resource");
        if (resources == null) {
            return;
        }
        for (JsonValue value : resources) {
            resource(value.asJsonObject(), rows, expandSearchParams);
        }
    }

    private static void resource(JsonObject resource, List<Row> rows, boolean expandSearchParams) {
        String type = string(resource, "type").orElse("?");

        JsonArray interactions = resource.getJsonArray("interaction");
        if (interactions != null) {
            List<String> codes = new ArrayList<>();
            for (JsonValue interaction : interactions) {
                string(interaction.asJsonObject(), "code").ifPresent(codes::add);
            }
            if (!codes.isEmpty()) {
                rows.add(new Row(ENTITY, type + ".interactions", String.join(", ", codes)));
            }
        }

        for (String field : new String[] {
                "versioning", "readHistory", "updateCreate",
                "conditionalCreate", "conditionalUpdate", "conditionalDelete"}) {
            JsonValue value = resource.get(field);
            if (value != null) {
                rows.add(new Row(ENTITY, type + "." + field, text(value)));
            }
        }

        JsonArray operations = resource.getJsonArray("operation");
        if (operations != null) {
            List<String> names = new ArrayList<>();
            for (JsonValue operation : operations) {
                string(operation.asJsonObject(), "name").ifPresent(names::add);
            }
            if (!names.isEmpty()) {
                rows.add(new Row(ENTITY, type + ".operations", String.join(", ", names)));
            }
        }

        JsonArray declared = resource.getJsonArray(SEARCH_PARAM);
        if (declared == null || declared.isEmpty()) {
            return;
        }
        if (expandSearchParams) {
            for (JsonValue searchParam : declared) {
                JsonObject object = searchParam.asJsonObject();
                rows.add(new Row(ENTITY,
                        type + "." + SEARCH_PARAM + "." + string(object, "name").orElse("?"),
                        string(object, "type").orElse("")));
            }
            return;
        }
        // Counted by kind rather than listed: the interesting question at a
        // glance is what a client can search on, not the roll-call. Counted by
        // ENTRY, so a statement that names a parameter twice reads as two —
        // summarising a defect away would defeat the point of looking.
        Map<String, Integer> byKind = new TreeMap<>();
        for (JsonValue searchParam : declared) {
            byKind.merge(string(searchParam.asJsonObject(), "type").orElse("?"), 1, Integer::sum);
        }
        String breakdown = byKind.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue() != 0
                        ? b.getValue() - a.getValue()
                        : a.getKey().compareTo(b.getKey()))
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining(", "));
        rows.add(new Row(ENTITY, type + "." + SEARCH_PARAM,
                declared.size() + " (" + breakdown + ")"));
    }

    private static Optional<String> string(JsonObject object, String field) {
        JsonValue value = object.get(field);
        return value == null ? Optional.empty() : Optional.of(text(value));
    }

    /** Rendered as it reads, not as JSON: a quoted "true" is noise in a table. */
    private static String text(JsonValue value) {
        return value instanceof JsonString string ? string.getString() : value.toString();
    }

    private static String property(BundleContext context, String key, String fallback) {
        String value = context.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
