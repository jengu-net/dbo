package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.table.ShellTable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A tenant's CapabilityStatement, flattened into one row per fact.
 *
 * <p>The statement is generated from what each configured type declares rather
 * than from a list applied to every type, so reading it back is how you see a
 * spec file's declarations as the promises clients are given: a type whose
 * identity is store-assigned has nothing to key a conditional write on, and
 * that shows up here as {@code conditionalCreate false}.
 *
 * <p>Read over the tenant's own {@code /metadata}, which is what a client sees.
 * The store facade can also produce a statement, but its single-argument form
 * is the one that does not know which operations were actually registered —
 * the two-argument form exists precisely because that list is passed rather
 * than guessed — and the security block is added at the serving edge. Reading
 * the registry would therefore report less than the tenant actually promises.
 * {@code /metadata} is the one path the guard exempts, so this needs no token.
 */
@Command(scope = "dbo-tenant", name = "capabilities",
        description = "Shows a tenant's CapabilityStatement as a table of facts.")
@Service
public class TenantCapabilitiesCommand implements Action {

    @Argument(index = 0, name = "tenant", required = true,
            description = "The tenant code, as dbo-tenant:list reports it.")
    private String tenant;

    /**
     * The row categories. A small closed vocabulary in the first column, so the
     * second can be a path — {@code Patient.versioning} — rather than the first
     * column carrying resource names and the second carrying field names, which
     * leaves nowhere to put a fact that is about neither.
     */
    private static final String SERVER = "server";
    private static final String ENTITY = "entity";

    @Option(name = "--search-params",
            description = "One row per search parameter instead of a count by kind.")
    private boolean searchParams;

    private ShellTable table;

    @Override
    public Object execute() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();

        ServiceReference<?>[] references =
                context.getAllServiceReferences(null, "(tenant=" + tenant + ")");
        if (references == null || references.length == 0) {
            System.out.println("No tenant '" + tenant + "' is being served."
                    + " dbo-tenant:list shows the ones that are.");
            return null;
        }

        String base = "http://" + property(context, "dbo.tenant.http.host", "127.0.0.1")
                + ":" + property(context, "dbo.tenant.http.port", "8090")
                + "/t/" + tenant + "/fhir";

        String statement;
        try {
            statement = get(base + "/metadata");
        } catch (Exception e) {
            System.out.println("Could not read " + base + "/metadata: " + e);
            return null;
        }

        table = new ShellTable();
        table.column("TYPE");
        table.column("NAME");
        table.column("VALUE");
        try (JsonReader reader = Json.createReader(new StringReader(statement))) {
            flatten(reader.readObject());
        }
        table.print(System.out);
        return null;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestProperty("Accept", "application/fhir+json");
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(15_000);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IllegalStateException("HTTP " + status);
            }
            try (InputStream body = connection.getInputStream()) {
                return new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void flatten(JsonObject statement) {
        for (String field : new String[] {"status", "kind", "fhirVersion", "date"}) {
            string(statement, field).ifPresent(value -> row(SERVER, field, value));
        }
        for (JsonValue format : statement.getJsonArray("format")) {
            row(SERVER, "format", text(format));
        }

        JsonArray rests = statement.getJsonArray("rest");
        if (rests == null) {
            return;
        }
        for (JsonValue value : rests) {
            rest(value.asJsonObject());
        }
    }

    private void rest(JsonObject rest) {
        string(rest, "mode").ifPresent(mode -> row(SERVER, "rest.mode", mode));

        JsonObject security = rest.getJsonObject("security");
        if (security != null) {
            JsonArray services = security.getJsonArray("service");
            if (services != null) {
                for (JsonValue service : services) {
                    JsonArray codings = service.asJsonObject().getJsonArray("coding");
                    if (codings != null) {
                        for (JsonValue coding : codings) {
                            string(coding.asJsonObject(), "code").ifPresent(
                                    code -> row(SERVER, "security.service", code));
                        }
                    }
                }
            }
            string(security, "description").ifPresent(
                    description -> row(SERVER, "security.description", description));
        }

        JsonArray resources = rest.getJsonArray("resource");
        if (resources == null) {
            return;
        }
        for (JsonValue resource : resources) {
            resource(resource.asJsonObject());
        }
    }

    private void resource(JsonObject resource) {
        String type = string(resource, "type").orElse("?");

        JsonArray interactions = resource.getJsonArray("interaction");
        if (interactions != null) {
            List<String> codes = new ArrayList<>();
            for (JsonValue interaction : interactions) {
                string(interaction.asJsonObject(), "code").ifPresent(codes::add);
            }
            if (!codes.isEmpty()) {
                row(ENTITY, type + ".interactions", String.join(", ", codes));
            }
        }

        for (String field : new String[] {
                "versioning", "readHistory", "updateCreate",
                "conditionalCreate", "conditionalUpdate", "conditionalDelete"}) {
            JsonValue value = resource.get(field);
            if (value != null) {
                row(ENTITY, type + "." + field, text(value));
            }
        }

        JsonArray operations = resource.getJsonArray("operation");
        if (operations != null) {
            List<String> names = new ArrayList<>();
            for (JsonValue operation : operations) {
                string(operation.asJsonObject(), "name").ifPresent(names::add);
            }
            if (!names.isEmpty()) {
                row(ENTITY, type + ".operations", String.join(", ", names));
            }
        }

        JsonArray declared = resource.getJsonArray("searchParam");
        if (declared == null || declared.isEmpty()) {
            return;
        }
        if (searchParams) {
            for (JsonValue searchParam : declared) {
                JsonObject object = searchParam.asJsonObject();
                row(ENTITY, type + ".searchParam." + string(object, "name").orElse("?"),
                        string(object, "type").orElse(""));
            }
            return;
        }
        // Counted by kind rather than listed: the interesting question at a
        // glance is what a client can search on, not the roll-call. Counted by
        // ENTRY, so a statement that names a parameter twice reads as two —
        // summarising a defect away would defeat the point of looking.
        Map<String, Integer> byKind = new TreeMap<>();
        for (JsonValue searchParam : declared) {
            String kind = string(searchParam.asJsonObject(), "type").orElse("?");
            byKind.merge(kind, 1, Integer::sum);
        }
        String breakdown = byKind.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue() != 0
                        ? b.getValue() - a.getValue()
                        : a.getKey().compareTo(b.getKey()))
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining(", "));
        row(ENTITY, type + ".searchParam", declared.size() + " (" + breakdown + ")");
    }

    private void row(String type, String name, String value) {
        table.addRow().addContent(type, name, value);
    }

    private static java.util.Optional<String> string(JsonObject object, String field) {
        JsonValue value = object.get(field);
        return value == null ? java.util.Optional.empty() : java.util.Optional.of(text(value));
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
