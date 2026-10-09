package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The claims an ID token and UserInfo carry about the person: who they are,
 * as a sign-in snapshot, and what they hold, as it stands now.
 *
 * <p>Two halves, read at different moments on purpose. <b>Who they are</b> is
 * read at sign-in, as the person themselves: on a tenant that vaults
 * identity, reading a name is a disclosure, and the person reading their own
 * is recorded as exactly that, once. Refreshes and UserInfo reuse what sign-in
 * read. <b>What they hold</b> is re-derived at every minting, because a role
 * withdrawn at an organisation has to be gone from the next token, not from
 * the next sign-in.
 */
final class PersonClaims {

    /** Self-access, in the vocabulary the trail already speaks. */
    static final String PATIENT_REQUESTED = "PATRQT";

    private static final java.util.regex.Pattern CLAIM_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    /** Why a set of claims could not be minted: an OAuth error code and its reason. */
    static final class Refused extends RuntimeException {
        final String error;

        Refused(String error, String reason) {
            super(reason);
            this.error = error;
        }
    }

    private final TenantAuthority authority;

    PersonClaims(TenantAuthority authority) {
        this.authority = authority;
    }

    /**
     * Who the person is, read as themselves. The read leaves one entry on the
     * trail with the person as its actor and self-access as its purpose.
     */
    Map<String, Object> identity(String personId, Optional<String> login) {
        Map<String, Object> identity = new LinkedHashMap<>();
        asThemselves(personId, () -> {
            Object named = null;
            for (String practitionerId : authority.linkedOfType(personId, "Practitioner")) {
                Optional<StoredObject> practitioner =
                        authority.subjects().get("Practitioner", practitionerId);
                if (practitioner.isPresent()) {
                    named = parsed(practitioner.get());
                    if (!Json.array(named, "name").isEmpty()) {
                        break;
                    }
                }
            }
            if (named == null || Json.array(named, "name").isEmpty()) {
                named = authority.subjects().get("Person", personId).map(PersonClaims::parsed)
                        .orElse(null);
            }
            if (named != null) {
                names(named, identity);
                locale(named).ifPresent(locale -> identity.put("locale", locale));
            }
            return null;
        });
        login.ifPresent(name -> identity.put("preferred_username", name));
        return identity;
    }

    /**
     * Everything an ID token or UserInfo carries beyond what the authority
     * itself says: the identity snapshot, the grants as held, and what the
     * application's contributor adds.
     */
    Map<String, Object> mint(String tenant, String personId, String clientId,
            List<String> scopes, Subject.Occasion occasion, long authTime, List<String> amr,
            Map<String, Object> identity, UserClaims contributor, int maxBytes) {
        Map<String, Object> claims = new LinkedHashMap<>(identity);
        List<Subject.Practitioner> practitioners = new ArrayList<>();
        Map<String, Set<String>> heldAt = new LinkedHashMap<>();
        Map<String, Subject.Organization> organisations = new LinkedHashMap<>();
        boolean signingIn = occasion == Subject.Occasion.SIGN_IN;
        for (String practitionerId : authority.linkedOfType(personId, "Practitioner")) {
            Optional<StoredObject> practitioner = signingIn
                    ? asThemselves(personId,
                            () -> authority.subjects().get("Practitioner", practitionerId))
                    : authority.subjects().get("Practitioner", practitionerId);
            if (practitioner.isEmpty()) {
                continue;
            }
            List<Subject.Role> roles = new ArrayList<>();
            for (StoredObject role : authority.rolesHeldBy(practitionerId)) {
                Object payload = parsed(role);
                boolean active = TenantAuthority.periodActive(payload)
                        && !Boolean.FALSE.equals(((Map<?, ?>) payload).get("active"));
                String organisationId = authority.organisationOf(payload);
                Optional<Subject.Organization> organisation = Optional.ofNullable(organisationId)
                        .flatMap(id -> organisations.containsKey(id)
                                ? Optional.of(organisations.get(id))
                                : authority.subjects().get("Organization", id).map(found ->
                                        new Subject.Organization(id, text(found))));
                organisation.ifPresent(found -> organisations.put(found.id(), found));
                List<String> codes = new ArrayList<>();
                for (Object code : Json.array(payload, "code")) {
                    for (Object coding : Json.array(code, "coding")) {
                        String roleCode = Json.strOpt(coding, "code");
                        if (roleCode != null) {
                            codes.add(roleCode);
                        }
                    }
                }
                roles.add(new Subject.Role(text(role), List.copyOf(codes), active, organisation));
                if (!active) {
                    continue;
                }
                for (String roleCode : codes) {
                    // Held where a grant gives it something, as the access
                    // token's roles are, and listed where it is held: a
                    // reader that wants the departments under it has the tree.
                    if (authority.grants(roleCode, organisationId)) {
                        heldAt.computeIfAbsent(organisationId == null ? "" : organisationId,
                                ignored -> new LinkedHashSet<>()).add(roleCode);
                    }
                }
            }
            practitioners.add(new Subject.Practitioner(text(practitioner.get()),
                    List.copyOf(roles)));
        }
        List<Object> grants = new ArrayList<>();
        Map<String, Object> named = new LinkedHashMap<>();
        heldAt.forEach((organisationId, roleCodes) -> {
            Map<String, Object> grant = new LinkedHashMap<>();
            if (!organisationId.isEmpty()) {
                grant.put("organization", organisationId);
                Subject.Organization organisation = organisations.get(organisationId);
                if (organisation != null) {
                    Object record = Json.parse(organisation.json());
                    Map<String, Object> about = new LinkedHashMap<>();
                    Optional.ofNullable(Json.strOpt(record, "name"))
                            .ifPresent(name -> about.put("name", name));
                    List<Object> identifiers = Json.array(record, "identifier");
                    if (!identifiers.isEmpty()) {
                        about.put("identifier", identifiers);
                    }
                    named.put(organisationId, about);
                }
            }
            grant.put("roles", List.copyOf(roleCodes));
            grants.add(grant);
        });
        claims.put("grants", grants);
        claims.put("organizations", named);

        if (contributor != null) {
            String person = (signingIn
                    ? asThemselves(personId, () -> authority.subjects().get("Person", personId))
                    : authority.subjects().get("Person", personId))
                    .map(PersonClaims::text).orElse("{}");
            Subject subject = new Subject(tenant, clientId, scopes, occasion, authTime, amr,
                    Optional.ofNullable((String) identity.get("preferred_username")), person,
                    practitioners, claims,
                    type -> linked(personId, type, signingIn),
                    () -> authority.subjects().select(Criteria.of("Organization")).stream()
                            .map(found -> new Subject.Organization(found.id(), text(found)))
                            .toList(),
                    reader(authority.subjects()));
            Map<String, Object> added;
            try {
                added = contributor.contribute(subject);
            } catch (RuntimeException failed) {
                throw new Refused("temporarily_unavailable",
                        "the application's claims for this person could not be made");
            }
            if (added != null) {
                for (Map.Entry<String, Object> claim : added.entrySet()) {
                    if (UserClaims.RESERVED.contains(claim.getKey())) {
                        throw new Refused("server_error", "the application tried to say '"
                                + claim.getKey() + "', which only the authority says");
                    }
                    if (!CLAIM_NAME.matcher(claim.getKey()).matches()
                            || !renderable(claim.getValue())) {
                        throw new Refused("server_error", "the application's claim '"
                                + claim.getKey() + "' is not a JSON name and value");
                    }
                    claims.put(claim.getKey(), claim.getValue());
                }
            }
        }
        int size = Json.render(claims).getBytes(StandardCharsets.UTF_8).length;
        if (size > maxBytes) {
            // Refused here rather than truncated wherever a cookie or a
            // header first runs out of room, which would drop claims nobody
            // chose to drop.
            throw new Refused("server_error", "the claims for this person are " + size
                    + " bytes, over the bound of " + maxBytes);
        }
        return claims;
    }

    private List<String> linked(String personId, String type, boolean signingIn) {
        Supplier<List<String>> read = () -> authority.linkedOfType(personId, type).stream()
                .map(id -> authority.subjects().get(type, id))
                .flatMap(Optional::stream).map(PersonClaims::text).toList();
        return signingIn ? asThemselves(personId, read) : read.get();
    }

    private static Subject.Reader reader(ObjectStore store) {
        return new Subject.Reader() {
            @Override
            public Optional<String> get(String type, String id) {
                return store.get(type, id).map(PersonClaims::text);
            }

            @Override
            public List<String> select(Criteria criteria) {
                return store.select(criteria).stream().map(PersonClaims::text).toList();
            }
        };
    }

    /**
     * A read made as the person, for the person: their own identity, under
     * the purpose that says so, with whatever this thread carried before put
     * back afterwards.
     */
    private static <T> T asThemselves(String personId, Supplier<T> read) {
        String actor = Caller.authenticated();
        String onBehalfOf = Caller.onBehalfOf();
        String run = Caller.run();
        Disclosure.Mode mode = Disclosure.mode();
        String purpose = Disclosure.purpose();
        Caller.set("Person/" + personId);
        Disclosure.set(Disclosure.Mode.INCLUDE, PATIENT_REQUESTED);
        try {
            return read.get();
        } finally {
            if (purpose == null && mode == Disclosure.Mode.OMIT) {
                Disclosure.clear();
            } else {
                Disclosure.set(mode, purpose);
            }
            Caller.clear();
            if (actor != null) {
                Caller.setChain(actor, onBehalfOf);
            }
            if (run != null) {
                Caller.setRun(run);
            }
        }
    }

    private static void names(Object resource, Map<String, Object> into) {
        List<Object> names = Json.array(resource, "name");
        if (names.isEmpty()) {
            // Somebody known by an identifier alone is still somebody: they
            // sign in and are told nothing about a name they never gave.
            return;
        }
        Object chosen = names.stream()
                .filter(name -> "official".equals(Json.strOpt(name, "use")))
                .findFirst().orElse(names.get(0));
        List<String> given = Json.strings(chosen, "given");
        String family = Json.strOpt(chosen, "family");
        String text = Json.strOpt(chosen, "text");
        if (!given.isEmpty()) {
            into.put("given_name", String.join(" ", given));
        }
        if (family != null) {
            into.put("family_name", family);
        }
        String whole = text != null ? text
                : (String.join(" ", given) + (family == null ? "" : " " + family)).trim();
        if (!whole.isEmpty()) {
            into.put("name", whole);
        }
    }

    /** R4 names a language as a concept; R5 wraps it, with a preference beside it. */
    private static Optional<String> locale(Object resource) {
        for (Object communication : Json.array(resource, "communication")) {
            Object concept = communication instanceof Map<?, ?> wrapped
                    && wrapped.containsKey("language") ? wrapped.get("language") : communication;
            for (Object coding : Json.array(concept, "coding")) {
                String code = Json.strOpt(coding, "code");
                if (code != null) {
                    return Optional.of(code);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean renderable(Object value) {
        return switch (value) {
            case null -> true;
            case String ignored -> true;
            case Number ignored -> true;
            case Boolean ignored -> true;
            case List<?> list -> list.stream().allMatch(PersonClaims::renderable);
            case Map<?, ?> map -> map.entrySet().stream().allMatch(entry ->
                    entry.getKey() instanceof String key && CLAIM_NAME.matcher(key).matches()
                            && renderable(entry.getValue()));
            default -> false;
        };
    }

    private static Object parsed(StoredObject record) {
        return Json.parse(text(record));
    }

    private static String text(StoredObject record) {
        return new String(record.payload(), StandardCharsets.UTF_8);
    }
}
