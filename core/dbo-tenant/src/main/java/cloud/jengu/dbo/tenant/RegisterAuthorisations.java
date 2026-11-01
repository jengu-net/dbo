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
 * <p><b>A purpose is kept as what it was agreed to</b>: who handles the data
 * and which data, read against the rows serving it each time. A purpose kept
 * as a name would let a row added under it later stand on an agreement given
 * before it existed.
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

    /** One purpose authorised: who agreed, when, and to what. */
    record Agreed(String by, Instant at, FleetRegister.Agreement agreement) {
    }

    /** Everything the tenant authorised at its door: rows by digest, purposes by code. */
    record Standing(Map<String, Given> rows, Map<String, Agreed> purposes) {
    }

    private final cloud.jengu.dbo.policy.PolicyObjectStore store;

    RegisterAuthorisations(cloud.jengu.dbo.policy.PolicyObjectStore store) {
        this.store = store;
    }

    /**
     * @param atAPlace whether the tenant is served here as a place of it,
     *                 where the record is a copy of its origin's that only
     *                 the stream from the origin writes
     */
    static List<TypeRegistration> registrations(boolean atAPlace) {
        return List.of(new TypeRegistration(TYPE, cloud.jengu.dbo.work.WorkModel.DOMAIN,
                IdentityClass.IDENTIFIER, Set.of(SYSTEM),
                atAPlace ? Handling.replicated() : Handling.storeAuthored(),
                extractor(), List.of()));
    }

    /** The record as it is stored, or empty where the tenant never authorised here. */
    Optional<StoredObject> current() {
        return stored();
    }

    /**
     * When the record standing was given, or empty where the tenant never
     * authorised at its door. A withdrawal is an authorisation too, so this
     * moves with every one, where a row's own time does not.
     */
    Optional<Instant> authorisedAt() {
        return stored().map(record -> {
            Object parsed = Json.parse(new String(record.payload(), StandardCharsets.UTF_8));
            String at = Json.objOpt(parsed, "at") instanceof String said ? said : null;
            // Absent from a record written before it was stamped: the latest
            // time anything in it was agreed to is the nearest it can say.
            return at != null ? Instant.parse(at) : standingOf(record).rows().values().stream()
                    .map(Given::at).max(Instant::compareTo).orElse(record.lastUpdated());
        });
    }

    /**
     * What the tenant authorised at its door, or empty where it has never
     * authorised anything here and its declaration is what is read.
     */
    Optional<Standing> given() {
        return stored().map(RegisterAuthorisations::standingOf);
    }

    /**
     * Authorises exactly these rows and these purposes, withdrawing every
     * other, and says so on the tenant's trail. A row authorised before keeps
     * who agreed to it and when, and so does a purpose agreed to the same
     * participants and types as before.
     *
     * @param purposes each purpose authorised, with what it is agreed to now
     * @return what stands now
     */
    Standing authorise(List<String> digests, Map<String, FleetRegister.Agreement> purposes,
            String by, Instant at) {
        Optional<StoredObject> standing = stored();
        Standing before = standing.map(RegisterAuthorisations::standingOf)
                .orElse(new Standing(Map.of(), Map.of()));
        Map<String, Given> now = new LinkedHashMap<>();
        for (String digest : digests) {
            now.put(digest, before.rows().getOrDefault(digest, new Given(by, at)));
        }
        Map<String, Agreed> agreed = new LinkedHashMap<>();
        purposes.forEach((code, agreement) -> {
            Agreed was = before.purposes().get(code);
            agreed.put(code, was != null && was.agreement().equals(agreement) ? was
                    : new Agreed(by, at, agreement));
        });
        StringBuilder payload = new StringBuilder("{\"key\":\"" + KEY + "\",\"at\":"
                + quoted(at.toString()) + ",\"rows\":[");
        String comma = "";
        for (Map.Entry<String, Given> row : now.entrySet()) {
            payload.append(comma).append("{\"digest\":").append(quoted(row.getKey()))
                    .append(",\"by\":").append(quoted(row.getValue().by()))
                    .append(",\"at\":").append(quoted(row.getValue().at().toString()))
                    .append('}');
            comma = ",";
        }
        payload.append("],\"purposes\":[");
        comma = "";
        for (Map.Entry<String, Agreed> purpose : agreed.entrySet()) {
            payload.append(comma).append("{\"code\":").append(quoted(purpose.getKey()))
                    .append(",\"by\":").append(quoted(purpose.getValue().by()))
                    .append(",\"at\":").append(quoted(purpose.getValue().at().toString()))
                    .append(",\"participants\":")
                    .append(listed(purpose.getValue().agreement().participants()))
                    .append(",\"types\":").append(listed(purpose.getValue().agreement().types()))
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
        added.removeAll(before.rows().keySet());
        List<String> withdrawn = new ArrayList<>(before.rows().keySet());
        withdrawn.removeAll(now.keySet());
        List<String> purposesWithdrawn = new ArrayList<>(before.purposes().keySet());
        purposesWithdrawn.removeAll(agreed.keySet());
        store.recordCustom("register-authorised", TYPE, id, Map.of(
                "by", by, "added", String.join(" ", added),
                "withdrawn", String.join(" ", withdrawn),
                "purposes", String.join(" ", agreed.keySet()),
                "purposesWithdrawn", String.join(" ", purposesWithdrawn)));
        return new Standing(now, agreed);
    }

    private static String quoted(String value) {
        return cloud.jengu.dbo.work.RunInitiator.quoted(value);
    }

    private static String listed(java.util.Collection<String> values) {
        return values.stream().map(RegisterAuthorisations::quoted)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private Optional<StoredObject> stored() {
        return store.getByIdentifier(TYPE, List.of(new Identifier(SYSTEM, KEY))).stream()
                .filter(found -> !found.deleted()).findFirst();
    }

    private static Standing standingOf(StoredObject record) {
        Object parsed = Json.parse(new String(record.payload(), StandardCharsets.UTF_8));
        Map<String, Given> rows = new LinkedHashMap<>();
        for (Object row : Json.objOpt(parsed, "rows") instanceof List<?> listed
                ? listed : List.of()) {
            rows.put(Json.str(row, "digest"), new Given(Json.str(row, "by"),
                    Instant.parse(Json.str(row, "at"))));
        }
        // Absent from a record written before purposes were authorised.
        Map<String, Agreed> purposes = new LinkedHashMap<>();
        for (Object purpose : Json.objOpt(parsed, "purposes") instanceof List<?> listed
                ? listed : List.of()) {
            purposes.put(Json.str(purpose, "code"), new Agreed(Json.str(purpose, "by"),
                    Instant.parse(Json.str(purpose, "at")), new FleetRegister.Agreement(
                            new java.util.TreeSet<>(Json.strings(purpose, "participants")),
                            new java.util.TreeSet<>(Json.strings(purpose, "types")))));
        }
        return new Standing(rows, purposes);
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
