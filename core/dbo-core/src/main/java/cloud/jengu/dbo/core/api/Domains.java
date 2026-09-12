package cloud.jengu.dbo.core.api;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where a domain's rows live.
 *
 * <p>A domain is a set of types that share tables and a feed. Most domains
 * share two schemas — {@code state} for what is current and {@code history}
 * for what was — and are told apart inside them by a prefix on the table name.
 * That is the right arrangement for a domain nobody ever moves on its own.
 *
 * <p>Some domains are moved on their own, and for those the arrangement is
 * wrong. A schema is the unit Postgres dumps, restores, drops and grants on:
 * a domain spread through {@code state} beside four others cannot be handed
 * over without naming its tables one by one and filtering rows by type inside
 * the shared ones, and a reader has to be told which of the tables in
 * {@code state} were meant. A domain that is separable says so HERE, once, and
 * gets a schema of its own — after which "all of it" is a schema name.
 *
 * <p>The rule is data rather than a property of a registration on purpose. A
 * schema belongs to a domain and not to a type, so two types of one domain
 * cannot disagree about it, and nothing that composes a table name has to be
 * given the answer by its caller.
 *
 * <p>The name inside a dedicated schema keeps its domain prefix, so
 * {@code definitions.definitions_data} rather than {@code definitions.data}.
 * It reads redundantly and it is deliberate: every catalogue scan that finds a
 * domain by looking for a table called something{@code _data} keeps working
 * with one more schema in its filter, rather than growing a second case that
 * is only exercised by one domain.
 */
public final class Domains {

    /**
     * What a face gave a tenant: its definitions, their history and every row
     * derived from one.
     *
     * <p>Separable because a face is cut once per release and handed to every
     * tenant that comes up on it, and because it is the one part of a tenant's
     * database that carries no person and therefore may travel as bytes.
     */
    public static final String DEFINITIONS = "definitions";

    /** Domains that are moved on their own, and the schema each is moved as. */
    private static final Map<String, String> OWN_SCHEMA = Map.of(DEFINITIONS, DEFINITIONS);

    private static final String STATE = "state";
    private static final String HISTORY = "history";

    private Domains() {}

    /** Whether this domain is moved on its own, and so has a schema to itself. */
    public static boolean separable(String domain) {
        return OWN_SCHEMA.containsKey(domain);
    }

    /** The schema holding what is current. */
    public static String schema(String domain) {
        return OWN_SCHEMA.getOrDefault(domain, STATE);
    }

    /** The schema holding what was. The same one, for a domain that has its own. */
    public static String historySchema(String domain) {
        return OWN_SCHEMA.getOrDefault(domain, HISTORY);
    }

    /**
     * The qualified prefix of the domain's current tables — {@code state.r4}
     * for an ordinary domain, so that {@code "%s_data".formatted(tables(d))}
     * names the table wherever it lives.
     */
    public static String tables(String domain) {
        return schema(domain) + "." + domain;
    }

    /** The same, for the history table. */
    public static String historyTables(String domain) {
        return historySchema(domain) + "." + domain;
    }

    /**
     * Every schema a domain's current tables can be in, for the catalogue
     * scans that find domains rather than being told them.
     */
    public static Set<String> schemas() {
        Set<String> all = new LinkedHashSet<>();
        all.add(STATE);
        all.addAll(OWN_SCHEMA.values());
        return all;
    }

    /** Every schema holding a domain's tables at all, current or historical. */
    public static Set<String> allSchemas() {
        Set<String> all = new LinkedHashSet<>(schemas());
        all.add(HISTORY);
        return all;
    }
}
