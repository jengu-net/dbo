package io.dbobjects;

import io.dbobjects.context.TestcontainersTestContext;
import io.dbobjects.db.postgres.Constants;
import io.dbobjects.parallel.NodeState;
import io.dbobjects.storage.Storage;
import io.dbobjects.testdomain.PersonStorage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static io.dbobjects.context.TestcontainersTestContext.randomName;
import static java.lang.String.format;
import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Slf4j
//@Execution(ExecutionMode.CONCURRENT)
public class NodeLifecycleTest {

    private static TestcontainersTestContext ctx;

    @BeforeAll
    public static void beforeAllTests() {
        ctx = new TestcontainersTestContext();
    }

    @AfterAll
    public static void afterAllTests() {
        if (ctx != null) {
            ctx.close();
            ctx = null;
        }
    }

    @Test
    public void testFirstNodeMustBecomeMaster() {
        Collection<Storage<?>> storages = of(new PersonStorage(randomName("myDomain_")));
        var node = ctx.createNode(randomName("app_"), randomName("masterNode_"), storages);
        assertNotNull(node);
        assertTrue(node.isMaster());
        // more detailed checks for master node
    }

    @Test
    public void testSecondNodeMustNotBecomeMaster() {
        var app = randomName("app_");
        var domain = randomName("my_domain_");
        Collection<Storage<?>> storages = of(new PersonStorage(domain));
        var masterNode = ctx.createNode(app, randomName("masterNode_"), storages);
        assertTrue(masterNode.isMaster());
        var slaveNode = ctx.createNode(app, randomName("slaveNode_"), storages);
        assertFalse(slaveNode.isMaster());
        ctx.sleepFor(500);
    }

    @Test
    public void testSecondNodeMustBecomeMasterAfterMasterTimeout() {
        var app = randomName("app_");
        var domain = randomName("my_domain_");
        Collection<Storage<?>> storages = of(new PersonStorage(domain));
        var masterNode = ctx.createNode(app, randomName("masterNode_"), storages).synchronize();
        assertTrue(masterNode.isMaster());

        var slaveNode = ctx.createNode(app, randomName("slaveNode_"), storages).synchronize();
        assertFalse(slaveNode.isMaster());

        ctx.makeOutDated(masterNode);
        slaveNode.synchronize();
        assertTrue(slaveNode.isMaster(),
            "when masterNode is outdated, the slaveNode must become master");
        ctx.sleepFor(500);
    }

    @Test
    public void testNodeGracefulShutdown() {
        var app = randomName("app_");
        var domain = randomName("my_domain_");
        var masterNodeId = randomName("masterNode_");
        Collection<Storage<?>> storages = of(new PersonStorage(domain));
        var master = ctx.createNode(app, masterNodeId, storages).synchronize();
        ctx.getApplicationState(app).ifPresentOrElse(
            appState -> assertEquals(masterNodeId, appState.getMasterNode(),
                "first started node should become master node"),
            () -> fail("application state must exist after node initialization")
        );
        ctx.getNodeState(masterNodeId).ifPresentOrElse(
            nodeState -> assertEquals(NodeState.StatusCode.ACTIVE.name(),
                nodeState.getNodeStatusCode().name(),
                "successfully initialized node status should be ACTIVE"),
            () -> fail("node state must exist after node initialization")
        );
        master.close();
        ctx.getApplicationState(app).ifPresentOrElse(
            Assertions::assertNotNull,
            () -> fail("application state must remain after closing of master node")
        );
        ctx.getNodeState(masterNodeId).ifPresentOrElse(
            nodeState -> assertEquals(NodeState.StatusCode.STOPPED.name(),
                nodeState.getNodeStatusCode().name(),
                "gracefully stopped node status should be STOPPED"),
            () -> fail("node state must remain after closing")
        );
    }

    @Test
    public void testNewMasterNodeCleanup() {
        var app = randomName("app_");
        var domain = randomName("my_domain_");
        var node1Id = randomName("node_");
        var node2Id = randomName("node_");
        Collection<Storage<?>> storages = of(new PersonStorage(domain));
        var node1 = ctx.createNode(app, node1Id, storages).synchronize();
        var node2 = ctx.createNode(app, node2Id, storages).synchronize();
        assertTrue(node1.synchronize().isMaster(), node1Id + " as a 1st node should become a master");
        assertFalse(node2.synchronize().isMaster(), node2Id + " as a 2nd node should become a slave");
        node1.close();

        assertTrue(node2.synchronize().isMaster(),
            format("after closing %s, %s should become a master", node1Id, node2Id));
    }

    @Test
    public void testMustStartWhenDBVersionEqualsNodeVersion() {
        var app = randomName("app_");
        var domain = randomName("my_domain_");
        var nodeId1 = randomName("node_");
        var nodeId2 = randomName("node_");
        var nodeId3 = randomName("node_");
        Collection<Storage<?>> storages = of(new PersonStorage(domain));
        var node1 = ctx.createNode(app, nodeId1, storages).synchronize();
        assertEquals(NodeState.StatusCode.ACTIVE, node1.getNodeStatusCode(),
            format("first node (%s) must be active", nodeId1));
        var node2 = ctx.createNode(app, nodeId2, storages).synchronize();
        assertEquals(NodeState.StatusCode.ACTIVE, node2.getNodeStatusCode(),
            format("second node (%s) must be active", nodeId2));
        node1.close();
        node2.close();
        var node3 = ctx.createNode(app, nodeId3, storages).synchronize();
        assertEquals(NodeState.StatusCode.ACTIVE, node3.getNodeStatusCode(),
            format("third node (%s) must be active", nodeId3));
    }

    @Test
    public void testMustNotStartWhenDbVersionIsNewerThanNodeVersion() {
        var app = randomName("app_");
        var domain = randomName("my_domain_");
        Collection<Storage<?>> st = of(new PersonStorage(domain));
        var node1 = ctx.createNode(cfg -> cfg.appCode(app).storages(st)).synchronize();
        assertEquals(NodeState.StatusCode.ACTIVE, node1.getNodeStatusCode(),
            "first node must be active");
        var node2 = ctx.createNode(cfg -> cfg.appCode(app).dbImplVer(0).storages(st)).synchronize();
        assertEquals(NodeState.StatusCode.PASSIVE, node2.getNodeStatusCode(),
            "node with older db implementation version must become passive");
        var node3 =
            ctx.createNode(cfg -> cfg.appCode(app).dbImplVer(Integer.MAX_VALUE).storages(st)).synchronize();
        assertEquals(NodeState.StatusCode.PASSIVE, node3.getNodeStatusCode(),
            "node with newer db implementation version must become passive");
        var node4 =
            ctx.createNode(cfg -> cfg.appCode(app).dbImplVer(Constants.DBO_VERSION).storages(st)).synchronize();
        assertEquals(NodeState.StatusCode.ACTIVE, node4.getNodeStatusCode(),
            "node with the same implementation version must become passive");
        assertTrue(node1.synchronize().isMaster(), "first node must remain master");
    }

    @Test
    @Disabled // FIXME: testNodeWithNewerAppVersionMustSwitchToMaster throws false positives too often
    public void testNodeWithNewerAppVersionMustSwitchToMaster() {
        var app = randomName("app_");
        var domain = randomName("my_domain_");
        var nodeId1 = randomName("node_");
        var nodeId2 = randomName("node_");
        Collection<Storage<?>> st = of(new PersonStorage(domain));
        var node1 = ctx.createNode(cfg -> cfg.appCode(app).appVer(1).nodeId(nodeId1).storages(st))
            .synchronize(); // sync the state with db
        assertEquals(NodeState.StatusCode.ACTIVE, node1.getNodeStatusCode(),
            "first node must be active");
        var node2 = ctx.createNode(cfg -> cfg.appCode(app).appVer(2).nodeId(nodeId2).storages(st))
            .synchronize(); // sync the state with db
        assertEquals(NodeState.StatusCode.STARTING, node2.getNodeStatusCode(),
            "node2 must wait for node1 confirmation");
        assertFalse(node1
            .synchronize() // get the latest state from db
            .isMaster());
        assertTrue(node2
            .synchronize() // switch appVer -> 2 & sync the state with db
            .isMaster());
        assertEquals(NodeState.StatusCode.ACTIVE, node2.getNodeStatusCode());
        assertEquals(2, node2.getDataApplicationVersion());
        assertEquals(2, node1
            .synchronize() // get the latest state (appVer = 2) from db
            .getDataApplicationVersion());

    }

}
