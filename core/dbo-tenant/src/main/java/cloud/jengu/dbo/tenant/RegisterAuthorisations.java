package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which rows of its register a tenant authorised, kept in its own store.
 *
 * <p><b>The tenant's record, not the provider's.</b> A declaration is the
 * deployment's account of what it serves, written wherever the provider keeps
 * it, so consent written there sits in a file the other party writes. Here
 * the tenant gives and withdraws it itself, at its own door, and the record
 * says who of it agreed to each row and when.
 *
 * <p>One record, replaced whole by each authorisation, because a tenant
 * authorises the register it read rather than a row at a time. Store-authored
 * and versioned: its history is every authorisation and withdrawal the tenant
 * made.
 */
final class RegisterAuthorisations {

    static final String TYPE = "RegisterAuthorisation";

    static final String SYSTEM = "urn:dbo:register-authorisation";

    /** The one record's key: a tenant authorises one register. */
    private static final String KEY = "register";

    /** One row authorised: who of the tenant agreed to it, and when. */
    record Given(String by, Instant at) {
    }

    private final cloud.jengu.dbo.policy.PolicyObjectStore store;

    RegisterAuthorisations(cloud.jengu.dbo.policy.PolicyObjectStore store) {
        this.store = store;
    }

    static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, cloud.jengu.dbo.work.WorkModel.DOMAIN,
                IdentityClass.IDENTIFIER, Set.of(SYSTEM), Handling.storeAuthored(),
                extractor(), List.of()));
    }

    /**
     * Each row the tenant authorised by digest, or empty where it has never
     * authorised one here and its declaration is what is read.
     */
    Optional<Map<String, Given>> given() {
        return standing().map(RegisterAuthorisations::rowsOf);
    }

    /**
     * Authorises exactly these rows, withdrawing every other, and says so on
     * the tenant's trail. A row authorised before keeps who agreed to it and
     * when.
     *
     * @return what stands now
     */
    Map<String, Given> authorise(List<String> digests, String by, Instant at) {
        Optional<StoredObject> standing = standing();
        Map<String, Given> before = standing.map(RegisterAuthorisations::rowsOf)
                .orElse(Map.of());
        Map<String, Given> now = new LinkedHashMap<>();
        for (String digest : digests) {
            now.put(digest, before.getOrDefault(digest, new Given(by, at)));
        }
        StringBuilder payload = new StringBuilder("{\"key\":\"" + KEY + "\",\"rows\":[");
        String comma = "";
        for (Map.Entry<String, Given> row : now.entrySet()) {
            payload.append(comma).append("{\"digest\":").append(cloud.jengu.dbo.work.RunInitiator.quoted(row.getKey()))
                    .append(",\"by\":").append(cloud.jengu.dbo.work.RunInitiator.quoted(row.getValue().by()))
                    .append(",\"at\":").append(cloud.jengu.dbo.work.RunInitiator.quoted(row.getValue().at().toString()))
                    .append('}');
            comma = ",";
        }
        byte[] bytes = payload.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        String id;
        if (standing.isPresent()) {
            id = standing.get().id();
            store.put(PutRequest.update(TYPE, id, standing.get().versionId(), bytes));
        } else {
            id = store.putIfAbsent(IdentityRef.identifier(SYSTEM, KEY),
                    PutRequest.create(TYPE, bytes)).id();
        }
        List<String> added = new ArrayList<>(now.keySet());
        added.removeAll(before.keySet());
        List<String> withdrawn = new ArrayList<>(before.keySet());
        withdrawn.removeAll(now.keySet());
        store.recordCustom("register-authorised", TYPE, id, Map.of(
                "by", by, "added", String.join(" ", added),
                "withdrawn", String.join(" ", withdrawn)));
        return now;
    }

    private Optional<StoredObject> standing() {
        return store.getByIdentifier(TYPE, List.of(new Identifier(SYSTEM, KEY))).stream()
                .filter(found -> !found.deleted()).findFirst();
    }

    private static Map<String, Given> rowsOf(StoredObject record) {
        Object parsed = Json.parse(new String(record.payload(), StandardCharsets.UTF_8));
        Map<String, Given> rows = new LinkedHashMap<>();
        for (Object row : Json.objOpt(parsed, "rows") instanceof List<?> listed
                ? listed : List.of()) {
            rows.put(Json.str(row, "digest"), new Given(Json.str(row, "by"),
                    Instant.parse(Json.str(row, "at"))));
        }
        return rows;
    }

    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Envelope envelope = new Envelope();
            envelope.identifier(SYSTEM, Json.str(Json.parse(
                    new String(payload, StandardCharsets.UTF_8)), "key"));
            envelope.value("key", EnvelopeValue.of(KEY));
            return envelope;
        };
    }
}
