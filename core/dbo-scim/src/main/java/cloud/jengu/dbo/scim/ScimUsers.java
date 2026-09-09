package cloud.jengu.dbo.scim;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.pdi.PersonVault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The User ↔ Person mapping (REQ-DBO-SCIM-USER-IS-THE-PERSON).
 *
 * <p>A SCIM User is the human the identity provider knows, and §14 makes the
 * Person the place a human's identifying data is authored — so the
 * {@code externalId} is claimed on the Person, name and emails are authored
 * there, and a Practitioner capacity is ensured and linked via
 * {@code Person.link} on create. That is the same linkage the authority
 * walks at token time, so a provisioned person's grants work with nothing
 * further. Deprovision is a state — {@code active=false} on both records —
 * never the vault's erasure ceremony
 * (REQ-DBO-SCIM-DEPROVISION-IS-A-STATE).
 *
 * <p>Everything here runs through the tenant's own policy-wrapped engine
 * behind the membrane: the caller and purpose the handler established make
 * every read a recorded disclosure, and writes land in the audit trail like
 * any other write (REQ-DBO-SCIM-EVERY-OP-IS-A-DISCLOSURE).
 */
final class ScimUsers {

    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private final ObjectStore engine;
    private final PersonVault vault;
    private final String system;

    ScimUsers(ObjectStore engine, PersonVault vault, String system) {
        this.engine = engine;
        this.vault = vault;
        this.system = system;
    }

    /** One page of every User in the namespace, id-ordered. */
    List<Map<String, Object>> list(int startIndex, int count) {
        // SCIM's startIndex is 1-based positional; the vault pages by keyset.
        // Walked from the start rather than offset in SQL: a staff directory
        // is hundreds, not millions, and positional pagination over a keyset
        // is honest about that.
        List<Map<String, Object>> users = new ArrayList<>();
        String after = null;
        int position = 0;
        List<String> ids;
        do {
            ids = vault.claimantsIn(system, after, 200);
            for (String personId : ids) {
                position++;
                if (position >= startIndex && users.size() < count) {
                    byPerson(personId).ifPresent(users::add);
                }
                after = personId;
            }
        } while (!ids.isEmpty() && users.size() < count);
        return users;
    }

    long total() {
        return vault.claimantsCount(system);
    }

    /** The User claiming {@code externalId}, resolved through the vault. */
    Optional<Map<String, Object>> byExternalId(String externalId) {
        return vault.findByIdentifier(system, externalId).flatMap(this::byPerson);
    }

    /** The Users holding {@code email} — userName resolves via the contact index. */
    List<Map<String, Object>> byUserName(String email) {
        List<Map<String, Object>> users = new ArrayList<>();
        for (String personId : vault.findAllByIdentifier(PersonVault.TELECOM_SYSTEM, email)) {
            byPerson(personId).ifPresent(users::add);
        }
        return users;
    }

    /**
     * The Person record this human is spoken about by here.
     *
     * <p>The vault answers with a PERSON and this surface serves a Person
     * RECORD. They were the same string only while the vault was keyed by the
     * record's own id, and the two callers mean different things: a lookup by
     * externalId or by email has found a human, while a {@code GET /Users/{id}}
     * already names the record. Collapsing them is how a directory read starts
     * answering about somebody else.
     */
    Optional<Map<String, Object>> byPerson(String personId) {
        return vault.recordsOf(personId, "Person").stream()
                .flatMap(record -> read(record).stream())
                .findFirst();
    }

    /** The User a Person record renders as — the id this surface hands out. */
    Optional<Map<String, Object>> read(String recordId) {
        return engine.get("Person", recordId)
                .filter(person -> !person.deleted())
                .map(this::rendered);
    }

    /**
     * Create: the Person authors the identity, the Practitioner capacity is
     * ensured and linked. An externalId already claimed surfaces as the
     * store's own {@code IdentityConflictException} — the handler answers 409.
     */
    Map<String, Object> create(Object user) {
        String externalId = required(user, "externalId");
        String practitionerId = engine.put(PutRequest.create("Practitioner",
                "{\"resourceType\":\"Practitioner\",\"active\":true}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        String personJson = personJson(user, externalId, practitionerId, true);
        String personId = engine.put(PutRequest.create("Person",
                personJson.getBytes(StandardCharsets.UTF_8))).id();
        return rendered(engine.get("Person", personId).orElseThrow());
    }

    /**
     * Replace: the whole User, RFC 7644 §3.5.1. {@code expectedVersion} from
     * If-Match, or null for an unconditional replace.
     */
    Optional<Map<String, Object>> replace(String personId, Object user, Long expectedVersion) {
        Optional<StoredObject> current = engine.get("Person", personId)
                .filter(person -> !person.deleted());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String externalId = required(user, "externalId");
        boolean active = Json.bool(user, "active", true);
        String practitionerId = linkedPractitioner(current.get());
        if (practitionerId != null) {
            engine.get("Practitioner", practitionerId).ifPresent(capacity ->
                    engine.put(PutRequest.update("Practitioner", practitionerId,
                            capacity.versionId(),
                            withActive(capacity.payload(), active))));
        }
        String personJson = personJson(user, externalId, practitionerId, active);
        engine.put(PutRequest.update("Person", personId,
                expectedVersion != null ? expectedVersion : current.get().versionId(),
                personJson.getBytes(StandardCharsets.UTF_8)));
        return Optional.of(rendered(engine.get("Person", personId).orElseThrow()));
    }

    // ------------------------------------------------------------ mapping

    private String personJson(Object user, String externalId, String practitionerId,
            boolean active) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("resourceType", "Person");
        person.put("active", active);
        person.put("identifier", List.of(
                Map.of("system", system, "value", externalId)));
        Object name = Json.objOpt(user, "name");
        if (name != null) {
            Map<String, Object> fhirName = new LinkedHashMap<>();
            String family = Json.strOpt(name, "familyName");
            String given = Json.strOpt(name, "givenName");
            if (family != null) {
                fhirName.put("family", family);
            }
            if (given != null) {
                fhirName.put("given", List.of(given));
            }
            if (!fhirName.isEmpty()) {
                person.put("name", List.of(fhirName));
            }
        }
        List<Object> telecom = new ArrayList<>();
        String userName = Json.strOpt(user, "userName");
        if (userName != null) {
            telecom.add(Map.of("system", "email", "value", userName));
        }
        for (Object email : Json.array(user, "emails")) {
            String value = Json.strOpt(email, "value");
            if (value != null && !value.equals(userName)) {
                telecom.add(Map.of("system", "email", "value", value));
            }
        }
        if (!telecom.isEmpty()) {
            person.put("telecom", telecom);
        }
        if (practitionerId != null) {
            person.put("link", List.of(Map.of(
                    "target", Map.of("reference", "Practitioner/" + practitionerId))));
        }
        return Json.render(person);
    }

    private Map<String, Object> rendered(StoredObject person) {
        Object parsed = Json.parse(new String(person.payload(), StandardCharsets.UTF_8));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("schemas", List.of(USER_SCHEMA));
        user.put("id", person.id());
        for (Object identifier : Json.array(parsed, "identifier")) {
            if (system.equals(Json.strOpt(identifier, "system"))) {
                user.put("externalId", Json.strOpt(identifier, "value"));
            }
        }
        user.put("active", Json.bool(parsed, "active", true));
        List<Object> names = Json.array(parsed, "name");
        if (!names.isEmpty()) {
            Object name = names.get(0);
            Map<String, Object> scimName = new LinkedHashMap<>();
            String family = Json.strOpt(name, "family");
            if (family != null) {
                scimName.put("familyName", family);
            }
            List<String> given = Json.strings(name, "given");
            if (!given.isEmpty()) {
                scimName.put("givenName", given.get(0));
            }
            user.put("name", scimName);
        }
        List<Object> emails = new ArrayList<>();
        for (Object point : Json.array(parsed, "telecom")) {
            if ("email".equals(Json.strOpt(point, "system"))
                    && Json.strOpt(point, "value") != null) {
                if (!user.containsKey("userName")) {
                    user.put("userName", Json.strOpt(point, "value"));
                }
                emails.add(Map.of("value", Json.strOpt(point, "value")));
            }
        }
        if (!emails.isEmpty()) {
            user.put("emails", emails);
        }
        user.put("meta", Map.of(
                "resourceType", "User",
                "version", "W/\"" + person.versionId() + "\""));
        return user;
    }

    private static String linkedPractitioner(StoredObject person) {
        Object parsed = Json.parse(new String(person.payload(), StandardCharsets.UTF_8));
        for (Object link : Json.array(parsed, "link")) {
            String reference = Json.strOpt(Json.objOpt(link, "target"), "reference");
            if (reference != null && reference.startsWith("Practitioner/")) {
                return reference.substring("Practitioner/".length());
            }
        }
        return null;
    }

    private static byte[] withActive(byte[] payload, boolean active) {
        Object parsed = Json.parse(new String(payload, StandardCharsets.UTF_8));
        if (parsed instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> record = (Map<String, Object>) m;
            record.put("active", active);
            return Json.render(record).getBytes(StandardCharsets.UTF_8);
        }
        return payload;
    }

    private static String required(Object user, String field) {
        String value = Json.strOpt(user, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a User needs '" + field + "'");
        }
        return value;
    }
}
