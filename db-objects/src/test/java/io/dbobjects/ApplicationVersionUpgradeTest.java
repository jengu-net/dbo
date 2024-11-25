package io.dbobjects;

import io.dbobjects.context.TestcontainersTestContext;
import io.dbobjects.storage.ContactStorage;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.PersonStorage;
import io.dbobjects.testdomain.Person;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.dbobjects.parallel.NodeState.StatusCode.ACTIVE;
import static io.dbobjects.parallel.NodeState.StatusCode.PASSIVE;
import static io.dbobjects.storage.StorageEvent.EventType.U;
import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class ApplicationVersionUpgradeTest {

    // TODO: all domains should be created after startup by master node
    // TODO: startup must fail if appVer is the same with dbAppVer but there are configuration differences:
    //         - configured storages are not matching
    //         - storage configuration does not match (domain, storage object version)

    @Disabled("until the test is fixed")
    @Test
    public void testAllDBDomainsShouldBeCreatedByMasterNode() {
        try (var ctx = new TestcontainersTestContext()) {
            var app1Code = "myApp";
            var app1Ver = 1;
            var personDomain = "persons";
            var node1 = ctx.createNode(cfg -> cfg
                            .nodeId("node1")
                            .appCode(app1Code)
                            .appVer(app1Ver)
                            .storages(of(new PersonStorage(personDomain))))
                    .synchronize();
            // TODO: verify existence of domain by simple person CRUD
            var node2 = ctx.createNode(cfg -> cfg
                            .nodeId("node2")
                            .appVer(2)
                            .storages(of(new PersonStorage("persons"), new ContactStorage("contacts"))))
                    .synchronize();
            ctx.synchronizeMasterSwitch(node1, node2);
            assertEquals(PASSIVE, node1.getNodeStatusCode(), "node1 as older app must be passive");
            assertEquals(ACTIVE, node2.getNodeStatusCode(), "node2 as newer app must be active");
            assertTrue(node2.isMaster());

            node2.acceptEvent(personDomain, U,
                    PayloadInfo.of(null, "Person", 1, TestcontainersTestContext.objectMapper.serialize(new Person().setName("John Doe")).getBytes(StandardCharsets.UTF_8)));
        }

    }

}
