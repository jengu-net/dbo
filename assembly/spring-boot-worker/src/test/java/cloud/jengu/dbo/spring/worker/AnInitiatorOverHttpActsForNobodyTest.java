package cloud.jengu.dbo.spring.worker;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An application holding a tenant over HTTP asks on its own work credential,
 * and the door records that credential's client: there is no person to carry.
 */
class AnInitiatorOverHttpActsForNobodyTest {

    @Test
    @DisplayName("an initiator over HTTP refuses to act for a person rather than start work "
            + "that names the application where it should name who, and acts for nobody as "
            + "itself")
    @Proving(DboPromises.PROC_WORK_IN_PROCESS_NAMES_THE_PERSON_IT_IS_FOR)
    void itRefusesToActForAPerson() {
        DboWorkerProperties.Lane lane = new DboWorkerProperties.Lane();
        lane.setTenant("st-jerome");
        lane.setBase(URI.create("http://127.0.0.1:9/t/st-jerome/"));
        DboWorkerProperties properties = new DboWorkerProperties();
        properties.setLanes(List.of(lane));
        DboInitiator initiator = new DboInitiator(properties, Map.of("st-jerome", () -> "t"));

        assertThrows(UnsupportedOperationException.class,
                () -> initiator.actingFor("Practitioner/p1"));
        assertSame(initiator, initiator.actingFor(null));
    }
}
