package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.transport.StreamCarrier;
import cloud.jengu.dbo.stream.SubstrateCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node stopped without closing its door, and started again over the same
 * substrate. Its last generation is still standing open in the substrate,
 * and nothing will ever answer from it.
 */
@Tag("integration")
class ADoorARunLeftOpenIsNotWaitedOutIT {

    private static final String TENANT = "left-open";

    @Test
    @DisplayName("an ask that finds the door a run left open is answered by the door that "
            + "replaced it within seconds, not after its patience")
    @Proving(DboPromises.PROC_A_RESTARTED_NODE_WAITS_OUT_NO_DOOR_IT_LEFT)
    void aDoorARunLeftOpenIsNotWaitedOut() throws Exception {
        PGSimpleDataSource substrate = new PGSimpleDataSource();
        substrate.setUrl(SharedPostgres.urlFor("ADoorARunLeftOpenIsNotWaitedOutIT_substrate"));
        substrate.setUser(SharedPostgres.get().getUsername());
        substrate.setPassword(SharedPostgres.get().getPassword());
        SubstrateCarrier carrier = new SubstrateCarrier(substrate);

        // The run before: a door that served, and that the substrate still
        // shows open, as one stopped without closing it leaves it.
        StreamCarrier.Door before = carrier.door(TENANT);
        before.serve((id, ask) -> "from the run before");
        awaitOpen(substrate, SubstrateCarrier.workflowId(TENANT, 1));
        before.close();
        try (Connection c = substrate.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE dbos.workflow_status SET status = 'PENDING' WHERE workflow_uuid = ?")) {
            ps.setString(1, SubstrateCarrier.workflowId(TENANT, 1));
            assertEquals(1, ps.executeUpdate(), "the run before left no door to find");
        }

        try (StreamCarrier.Asker asker = carrier.asker(TENANT, "participant")) {
            long began = System.nanoTime();
            CompletableFuture<Optional<String>> asked = CompletableFuture.supplyAsync(
                    () -> asker.ask("ask-1", "hello", Duration.ofSeconds(60)));
            // The node comes back: its door opens again over the same substrate.
            Thread.sleep(1_000);
            try (StreamCarrier.Door again = carrier.door(TENANT)) {
                again.serve((id, ask) -> "answered");

                Optional<String> answer = asked.get(90, TimeUnit.SECONDS);
                long took = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began);

                assertEquals(Optional.of("answered"), answer,
                        "the ask was not answered by the door that replaced the one left open");
                assertTrue(took < 15, "the ask waited out the door a run left open: " + took
                        + "s of a 60s patience");
            }
        }
    }

    /** Waits until the substrate shows the generation begun. */
    private static void awaitOpen(PGSimpleDataSource substrate, String workflow) throws Exception {
        long giveUp = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < giveUp) {
            try (Connection c = substrate.getConnection();
                    PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM dbos.workflow_status WHERE workflow_uuid = ?")) {
                ps.setString(1, workflow);
                if (ps.executeQuery().next()) {
                    return;
                }
            } catch (java.sql.SQLException notYet) {
                // the substrate's schema is made as the door launches
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the door never opened " + workflow);
    }
}
