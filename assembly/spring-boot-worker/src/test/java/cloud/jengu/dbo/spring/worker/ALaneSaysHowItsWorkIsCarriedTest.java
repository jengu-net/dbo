package cloud.jengu.dbo.spring.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a lane's work travels, and where the application asks for work, are
 * two different things.
 *
 * <p>An application beside the store that both asks a tenant for work and
 * performs it asks at the tenant's step door, over HTTP with a credential, and
 * may have the work itself carried by the deployment's substrate. A lane
 * naming no base is the worker with nothing to ask; a lane naming a base and
 * {@code carrier: substrate} is the application that asks.
 */
class ALaneSaysHowItsWorkIsCarriedTest {

    @Test
    @DisplayName("a lane naming a base and carrier: substrate is carried by the substrate and "
            + "keeps the credential it asks with")
    void aBaseAndASubstrateCarrier() {
        DboWorkerProperties properties = new DboWorkerProperties();
        DboWorkerProperties.Lane asking = lane("st-jerome", "http://127.0.0.1:1/t/st-jerome/");
        asking.setCarrier("substrate");
        DboWorkerProperties.Lane bare = lane("gringotts", null);
        DboWorkerProperties.Lane overHttp = lane("hogwarts", "http://127.0.0.1:1/t/hogwarts/");
        properties.setLanes(List.of(asking, bare, overHttp));

        assertTrue(asking.overTheSubstrate(), "carrier: substrate was not honoured");
        assertTrue(bare.overTheSubstrate(), "a lane naming no base is not over the substrate");
        assertFalse(overHttp.overTheSubstrate(), "a lane naming a base and nothing else moved");
        assertEquals("st-jerome,gringotts", properties.tenantsOverTheSubstrate());
        assertEquals(java.util.Set.of("st-jerome", "hogwarts"),
                DboWorker.tokensFor(properties, Map.of()).keySet(),
                "the credential an application asks with went missing, or one was invented "
                        + "for a lane with nowhere to sign in");
    }

    @Test
    @DisplayName("a lane naming no base cannot be asked for work, and says so")
    void nothingToAskWithoutABase() {
        DboWorkerProperties properties = new DboWorkerProperties();
        properties.setLanes(List.of(lane("gringotts", null)));
        DboInitiator initiator = new DboInitiator(properties, Map.of());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> initiator.starting("gringotts", "care.records.register", Map.of()));
        assertTrue(refused.getMessage().contains("carrier: substrate"), refused.getMessage());
    }

    private static DboWorkerProperties.Lane lane(String tenant, String base) {
        DboWorkerProperties.Lane lane = new DboWorkerProperties.Lane();
        lane.setTenant(tenant);
        if (base != null) {
            lane.setBase(URI.create(base));
            DboWorkerProperties.Token token = new DboWorkerProperties.Token();
            token.setClientId("a-client");
            token.setClientSecret("a-secret");
            lane.setToken(token);
        }
        return lane;
    }
}
