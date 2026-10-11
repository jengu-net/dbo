package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two nodes on one Postgres server, both serving the same tenant code — a face
 * root on each, or a tenant's own place on two sites — each under a
 * namespace of its own.
 */
@Tag("integration")
class NodesShareAServerUnderNamespacesIT {

    private static final String CODE = "shared";

    @Test
    @DisplayName("two nodes serving one tenant code on one server each keep their own database, "
            + "and one letting the tenant go leaves the other's in place")
    @Proving(DboPromises.TEN_NODES_SHARE_A_SERVER_UNDER_NAMESPACES)
    void nodesKeepTheirOwnDatabases() throws Exception {
        String admin = SharedPostgres.urlFor("NodesShareAServerUnderNamespacesIT");
        String user = SharedPostgres.get().getUsername();
        String password = SharedPostgres.get().getPassword();
        LocalDatabasePerTenantProvisioner nodeA =
                new LocalDatabasePerTenantProvisioner(admin, user, password, "node-a");
        LocalDatabasePerTenantProvisioner nodeB =
                new LocalDatabasePerTenantProvisioner(admin, user, password, "node-b");
        TenantSpec spec = TenantSpec.parse("""
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(CODE));
        try {
            DataSource a = nodeA.provision(spec).dataSource();
            DataSource b = nodeB.provision(spec).dataSource();
            write(a, "kept by node a");
            write(b, "kept by node b");

            assertEquals("kept by node a", read(a), "node a reads another node's database");
            assertEquals("kept by node b", read(b), "node b reads another node's database");
            assertTrue(exists(admin, user, password, "node_a_tenant_" + CODE));
            assertTrue(exists(admin, user, password, "node_b_tenant_" + CODE));

            nodeA.deprovision(CODE);

            assertFalse(exists(admin, user, password, "node_a_tenant_" + CODE),
                    "node a's database outlived the tenant it let go");
            assertTrue(exists(admin, user, password, "node_b_tenant_" + CODE),
                    "one node letting the tenant go dropped another node's database");
            assertEquals("kept by node b", read(b),
                    "one node letting the tenant go took another node's records");
        } finally {
            nodeB.deprovision(CODE);
            nodeA.close();
            nodeB.close();
        }
    }

    private static void write(DataSource ds, String note) throws Exception {
        try (Connection c = ds.getConnection();
                PreparedStatement create = c.prepareStatement(
                        "CREATE TABLE IF NOT EXISTS public.note (said text)");
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO public.note (said) VALUES (?)")) {
            create.execute();
            insert.setString(1, note);
            insert.executeUpdate();
        }
    }

    private static String read(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT said FROM public.note");
                ResultSet rs = ps.executeQuery()) {
            StringBuilder said = new StringBuilder();
            while (rs.next()) {
                said.append(said.length() == 0 ? "" : "|").append(rs.getString(1));
            }
            return said.toString();
        }
    }

    private static boolean exists(String admin, String user, String password, String database)
            throws Exception {
        try (Connection c = DriverManager.getConnection(admin, user, password);
                PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
