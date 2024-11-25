package io.dbobjects.parallel;

import io.dbobjects.DBOApplicationConfig;
import io.dbobjects.nodesync.SyncedNodeState;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface NodeState {

    static SyncedNodeState initialState(DBOApplicationConfig appConfig) {
        var instance = new SyncedNodeState();
        instance.setAppCode(appConfig.getApplicationCode());
        instance.setNodeId(appConfig.getNodeId());
        instance.setAppVersion(appConfig.getApplicationVersion());
        return instance;
    }

    NodeState.StatusCode getNodeStatusCode();

    String getAppCode();

    String getNodeId();

    Long getNodeHeartbeat();

    String getKnownAppStateKey();

    int getAppVersion();

    int getDboVersion();

    String getDbType();

    /**
     * Node state. Initial state is STOPPED.
     * Critical conditions needed to be met for avoiding the node falling to the FAILURE state:
     * <pre>
     *   - The node must be able to exchange the state between other nodes (send and receive)
     * </pre>
     * All other non-critical reasons for not being operational will put the node to the PASSIVE
     * state.
     */
    @ToString
    enum StatusCode {
        /**
         * Node is stopped and can be started again. Startup may be activated automatically.
         * Possible transitions:
         * <pre>
         *   STARTING - when trying to start the node
         * </pre>
         */
        STOPPED("STARTING"),
        /**
         * When the node is ordered to be started and startup is in progress.
         * Possible transitions:
         * <pre>
         *   ACTIVE - when startup is succeeded
         *   FAILURE - when the critical conditions are not met
         * </pre>
         */
        STARTING("ACTIVE", "FAILURE", "PASSIVE", "STOPPING"),
        /**
         * When the node is started up and ready to operate. This is the only state where node processes
         * operational orders. In earlier stage It may collect the tasks for future processing.
         * Possible transitions:
         * <pre>
         *   STOPPING - when shutdown is asked
         *   FAILURE - when something critical happened
         *   PASSIVE - when the node can not operate bu the reason is not critical.
         * </pre>
         */
        ACTIVE("STOPPING", "FAILURE", "PASSIVE"),
        /**
         * When the node can not operate, but it can communicate with other nodes.
         * NB: Master node can be PASSIVE.
         * Possible transitions:
         * <pre>
         *   STOPPING - when shutdown is asked
         *   FAILURE - when something critical happened
         *   ACTIVE - when the node is fully operational again.
         * </pre>
         */
        PASSIVE("STOPPING", "FAILURE", "ACTIVE"),
        /**
         * When the shutdown is asked and the node switched to the shutdown sequence.
         * Possible transitions:
         * <pre>
         *   STOPPED - when the shutdown is succeeded
         *   FAILURE - when it is not possible to report to the cluster of successful shutdown. This
         *             is useful for logging locally the status of the shutdown;
         * </pre>
         */
        STOPPING("STOPPED", "FAILURE"),
        /**
         * When the node is stopped because of the critical reasons listed in class definition.
         * Possible transitions:
         * <pre>
         *   STARTING - when startup is asked (critical issues are removed)
         * </pre>
         */
        FAILURE("STARTING");

        @Getter
        @ToString.Exclude
        private final Collection<String> allowedTransitions;

        StatusCode(String... allowedTransitionStrings) {
            this.allowedTransitions = List.of(allowedTransitionStrings);
        }

        public static void verifySetup() {
            for (var state : StatusCode.values()) {
                for (var allowedTransitionString : state.allowedTransitions) {
                    try {
                        StatusCode.valueOf(allowedTransitionString);
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalStateException(String.format(
                            "Illegal transition String <%s> please check the enum %s.%s. Allowed values: %s",
                            allowedTransitionString, StatusCode.class.getName(), state.name(),
                            Stream.of(StatusCode.values()).map(StatusCode::name).collect(Collectors.toList())));
                    }
                }
            }
        }

        public boolean isTransitionAllowed(StatusCode newState) {
            return this.equals(newState) || (newState != null && allowedTransitions.contains(newState.name()));
        }
    }

    @Getter
    @Setter
    class NodeStateAttributes {
        private Integer nodeAppVer;
        private Integer nodeDboVer;
    }
}
