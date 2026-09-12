package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.TypeRegistry;
import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.IndexSpec;
import cloud.jengu.dbo.core.api.TypeRegistration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Idempotent schema setup under a Postgres advisory lock (cluster-safe: a
 * concurrent node waits, then finds the DDL already applied). Schemas are
 * split state / history per §11 so the maintenance export can dump history by
 * schema; the dbos schema is reserved for the durable-work slice.
 *
 * <p>Identifier interpolation note: domain and type names are validated
 * against strict patterns at registration ({@link TypeRegistration}), and
 * envelope paths at construction — the only strings composed into DDL are
 * these validated identifiers; every value is a bound parameter.
 */
public final class SchemaManager {

    private static final long LOCK_KEY = 0x64626F5F636F7265L; // "dbo_core"

    private final DataSource dataSource;

    public SchemaManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void ensureSchema(TypeRegistry registry) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try {
                execute(c, "SELECT pg_advisory_lock(" + LOCK_KEY + ")");
                execute(c, "CREATE SCHEMA IF NOT EXISTS state");
                execute(c, "CREATE SCHEMA IF NOT EXISTS history");
                execute(c, "CREATE SCHEMA IF NOT EXISTS dbos");
                // A separable domain's own schema, created because a domain
                // is registered rather than because a release said so: the
                // schema and the tables in it arrive together or not at all.
                for (String domain : registry.domains()) {
                    for (String schema : schemasOf(domain)) {
                        execute(c, "CREATE SCHEMA IF NOT EXISTS " + schema);
                    }
                }
                for (String domain : registry.domains()) {
                    createDomainTables(c, domain);
                }
                applyIndexes(c, registry);
            } finally {
                execute(c, "SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("schema setup failed", e);
        }
    }

    public void applyIndexes(TypeRegistry registry) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            applyIndexes(c, registry);
        } catch (SQLException e) {
            throw new IllegalStateException("index setup failed", e);
        }
    }

    /** The schemas this domain's tables need, beyond the shared ones. */
    private static java.util.Set<String> schemasOf(String domain) {
        if (!Domains.separable(domain)) {
            return java.util.Set.of();
        }
        // The two are the same schema for a domain that has one, which is the
        // point of it: what is current and what was travel together.
        return new java.util.LinkedHashSet<>(
                java.util.List.of(Domains.schema(domain), Domains.historySchema(domain)));
    }

    private void createDomainTables(Connection c, String domain) throws SQLException {
        // Tables are named by where they are; indexes by the domain alone,
        // because an index is created in its table's schema and may not be
        // qualified.
        String d = Domains.tables(domain);
        String h = Domains.historyTables(domain);
        execute(c, """
                CREATE TABLE IF NOT EXISTS %s_data (
                  id uuid PRIMARY KEY,
                  type text NOT NULL,
                  version_id bigint NOT NULL,
                  last_updated timestamptz NOT NULL,
                  envelope jsonb NOT NULL,
                  payload bytea NOT NULL,
                  deleted boolean NOT NULL DEFAULT false,
                  payload_version text NOT NULL DEFAULT '1',
                  chain_hash bytea,
                  shape jsonb
                )""".formatted(d));
        // Existing domains gain the link column; rows written before it
        // carry null, which the verifier reports as unchained rather than as
        // broken — an honest distinction, since nothing was ever attested.
        execute(c, "ALTER TABLE %s_data ADD COLUMN IF NOT EXISTS chain_hash bytea".formatted(d));
        // The shape stamp: the pack profile versions each version was
        // validated under — a fact of the accept event, beside the payload
        // like payload_version and chain_hash (REQ-DBO-SHAPE-*). Rows from
        // before the column carry null, which reads as unstamped.
        execute(c, "ALTER TABLE %s_data ADD COLUMN IF NOT EXISTS shape jsonb".formatted(d));
        execute(c, "CREATE INDEX IF NOT EXISTS %s_data_type_ix ON %s_data (type, last_updated, id)"
                .formatted(domain, d));
        execute(c, "CREATE INDEX IF NOT EXISTS %s_data_env_gin ON %s_data USING gin (envelope jsonb_path_ops)"
                .formatted(domain, d));
        execute(c, """
                CREATE TABLE IF NOT EXISTS %s_identifier (
                  type text NOT NULL,
                  system text NOT NULL,
                  value text NOT NULL,
                  object_id uuid NOT NULL,
                  identity boolean NOT NULL,
                  PRIMARY KEY (type, system, value, object_id)
                )""".formatted(d));
        // the no-implicit-merge rule, enforced at the database:
        execute(c, ("CREATE UNIQUE INDEX IF NOT EXISTS %s_identity_claim ON %s_identifier "
                + "(type, system, value) WHERE identity").formatted(domain, d));
        execute(c, "CREATE INDEX IF NOT EXISTS %s_identifier_obj_ix ON %s_identifier (object_id)"
                .formatted(domain, d));
        // Owned here rather than by the sync engine: the serving path
        // reads a record's origin to say Meta.source, and a subselect against
        // a table only dependent tenants have would break every tenant
        // without one. The sync engine's own CREATE IF NOT EXISTS remains and
        // is now a no-op.
        execute(c, """
                CREATE TABLE IF NOT EXISTS %s_sync_shadow (
                  dependency text NOT NULL,
                  object_id uuid NOT NULL,
                  type text NOT NULL,
                  source_version_id bigint NOT NULL,
                  payload bytea NOT NULL,
                  deleted boolean NOT NULL,
                  payload_version text NOT NULL,
                  parked_at timestamptz NOT NULL,
                  shadows_object_id uuid,
                  PRIMARY KEY (dependency, object_id)
                )""".formatted(d));
        execute(c, """
                CREATE TABLE IF NOT EXISTS %s_sync_origin (
                  object_id uuid PRIMARY KEY,
                  dependency text NOT NULL,
                  type text NOT NULL,
                  source_version_id bigint NOT NULL,
                  applied_payload_version text,
                  synced_at timestamptz NOT NULL
                )""".formatted(d));
        execute(c, """
                CREATE TABLE IF NOT EXISTS %s_reference (
                  owner_id uuid NOT NULL,
                  ref_type text NOT NULL,
                  target_type text NOT NULL,
                  target_id text NOT NULL,
                  PRIMARY KEY (owner_id, ref_type, target_type, target_id)
                )""".formatted(d));
        execute(c, "CREATE INDEX IF NOT EXISTS %s_reference_target_ix ON %s_reference (target_type, target_id)"
                .formatted(domain, d));
        // xact_id is the gap-free-read barrier: seq is assigned at
        // insert but commits interleave; a reader that trusts "seq > cursor"
        // alone can skip a slow transaction's rows. Readers only deliver rows
        // whose xact_id is below the current snapshot's xmin — everything
        // there is finished, so delivery is gap-free in seq order.
        execute(c, """
                CREATE TABLE IF NOT EXISTS %s_outbox (
                  seq bigserial PRIMARY KEY,
                  object_id uuid NOT NULL,
                  type text NOT NULL,
                  version_id bigint NOT NULL,
                  kind text NOT NULL,
                  committed_at timestamptz NOT NULL DEFAULT now(),
                  xact_id xid8 NOT NULL DEFAULT pg_current_xact_id()
                )""".formatted(d));
        execute(c, """
                CREATE TABLE IF NOT EXISTS %s_consumer (
                  name text PRIMARY KEY,
                  seq bigint NOT NULL,
                  cursor_xid xid8 NOT NULL DEFAULT '0',
                  updated_at timestamptz NOT NULL DEFAULT now()
                )""".formatted(d));
                execute(c, "ALTER TABLE %s_consumer ADD COLUMN IF NOT EXISTS cursor_xid xid8 NOT NULL DEFAULT '0'".formatted(d));
                // Commit fence: the feed orders by (xact_id, seq) — xid-major,
                // so no commit can ever land behind the cursor
                execute(c, "CREATE INDEX IF NOT EXISTS %s_outbox_xid_seq ON %s_outbox (xact_id, seq)".formatted(domain, d));
        execute(c, """
                CREATE TABLE IF NOT EXISTS %s_history (
                  id uuid NOT NULL,
                  version_id bigint NOT NULL,
                  type text NOT NULL,
                  last_updated timestamptz NOT NULL,
                  payload bytea NOT NULL,
                  deleted boolean NOT NULL,
                  payload_version text NOT NULL DEFAULT '1',
                  chain_hash bytea,
                  shape jsonb,
                  PRIMARY KEY (id, version_id)
                )""".formatted(h));
        execute(c, "ALTER TABLE %s_history ADD COLUMN IF NOT EXISTS chain_hash bytea"
                .formatted(h));
        execute(c, "ALTER TABLE %s_history ADD COLUMN IF NOT EXISTS shape jsonb"
                .formatted(h));
    }

    private void applyIndexes(Connection c, TypeRegistry registry) throws SQLException {
        for (TypeRegistration t : registry.all()) {
            for (IndexSpec ix : t.indexes()) {
                String expr = Sql.typedPathExpression(ix.path(), ix.kind());
                String name = "%s_%s_%s_ix".formatted(t.domain(), t.typeName().toLowerCase(), ix.path().toLowerCase());
                execute(c, "CREATE INDEX IF NOT EXISTS %s ON %s_data ((%s)) WHERE type = '%s'"
                        .formatted(name, Domains.tables(t.domain()), expr, t.typeName()));
            }
        }
    }

    private void execute(Connection c, String ddl) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ddl)) {
            ps.execute();
        }
    }
}
