package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Criteria;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The person a set of claims is minted for, as the authority already loaded
 * them.
 *
 * <p>Records are the resource's own JSON, so a contributor reads them in the
 * FHIR version the tenant serves and the store stays independent of it. What
 * was not loaded is behind a handle that loads on first use and at most once
 * per minting: a contributor that does not ask costs nothing.
 *
 * <p><b>Identifying elements are present only when the claims are minted at
 * sign-in.</b> That is the one moment the authority reads the person's own
 * identity for them, and the read is recorded as theirs. A refresh or a
 * UserInfo call re-derives what the person holds, and reads nobody's
 * identity again.
 */
public final class Subject {

    /** Why the claims are being minted. */
    public enum Occasion { SIGN_IN, REFRESH, USERINFO }

    /** An organisation, as its own record. */
    public record Organization(String id, String json) {}

    /**
     * A role the person holds, with the organisation it is held at.
     *
     * @param codes  the role codes it carries
     * @param active whether its period covers today and it is not marked
     *               inactive
     */
    public record Role(String json, List<String> codes, boolean active,
            Optional<Organization> organization) {}

    /** A practitioner record of the person's, and the roles it holds. */
    public record Practitioner(String json, List<Role> roles) {}

    /** Reads this tenant's records, read-only and without anybody's identity. */
    public interface Reader {
        Optional<String> get(String type, String id);

        List<String> select(Criteria criteria);
    }

    private final String tenant;
    private final String clientId;
    private final List<String> scopes;
    private final Occasion occasion;
    private final long authTime;
    private final List<String> amr;
    private final Optional<String> login;
    private final String person;
    private final List<Practitioner> practitioners;
    private final Map<String, Object> defaults;
    private final Function<String, List<String>> linkedLoader;
    private final Supplier<List<Organization>> treeLoader;
    private final Reader store;
    private final Map<String, List<String>> linked = new java.util.HashMap<>();
    private List<Organization> tree;

    Subject(String tenant, String clientId, List<String> scopes, Occasion occasion,
            long authTime, List<String> amr, Optional<String> login, String person,
            List<Practitioner> practitioners, Map<String, Object> defaults,
            Function<String, List<String>> linkedLoader,
            Supplier<List<Organization>> treeLoader, Reader store) {
        this.tenant = tenant;
        this.clientId = clientId;
        this.scopes = List.copyOf(scopes);
        this.occasion = occasion;
        this.authTime = authTime;
        this.amr = List.copyOf(amr);
        this.login = login;
        this.person = person;
        this.practitioners = List.copyOf(practitioners);
        this.defaults = Map.copyOf(defaults);
        this.linkedLoader = linkedLoader;
        this.treeLoader = treeLoader;
        this.store = store;
    }

    public String tenant() {
        return tenant;
    }

    /** The client the claims go to. What one application is told, another is not. */
    public String clientId() {
        return clientId;
    }

    public List<String> scopes() {
        return scopes;
    }

    public Occasion occasion() {
        return occasion;
    }

    /** When the person signed in, carried unchanged through every refresh. */
    public long authTime() {
        return authTime;
    }

    /** How they signed in: {@code pwd}, or the brokers the hub ran. */
    public List<String> amr() {
        return amr;
    }

    /** The login they signed in with, where they signed in with one. */
    public Optional<String> login() {
        return login;
    }

    /** The Person record. */
    public String person() {
        return person;
    }

    public List<Practitioner> practitioners() {
        return practitioners;
    }

    /** The claims about to be issued before anything is added. */
    public Map<String, Object> defaults() {
        return defaults;
    }

    /** Records of this type the Person links to: their Patient, their RelatedPerson. */
    public List<String> linked(String type) {
        return linked.computeIfAbsent(type, linkedLoader);
    }

    /** Every organisation of this tenant, for a reader that decides descendants itself. */
    public List<Organization> organisationTree() {
        if (tree == null) {
            tree = List.copyOf(treeLoader.get());
        }
        return tree;
    }

    public Reader store() {
        return store;
    }
}
