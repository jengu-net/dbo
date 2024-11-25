package io.dbobjects;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collection;

@Getter
@Setter
@ToString
public class ApplicationState {
    private String appCode;
    private int appVer;
    private int dboVer;
    private String dbType;
    private String masterNode;
    private boolean blocked;

    private String appStatusKey;
    @ToString.Exclude
    private ApplicationStateAttributes attributes = new ApplicationStateAttributes();

    public static ApplicationState initialState(DBOApplicationConfig appConfig) {
        var state = new ApplicationState();
        state.setAppCode(appConfig.getApplicationCode());
        state.setMasterNode(appConfig.getNodeId());
        state.setBlocked(false);
        state.setAppVer(appConfig.getApplicationVersion());
        return state;
    }

    public void calculateAppStatusKey() {
        var keys = new ArrayList<String>();
        if (attributes != null && attributes.getDomains() != null) {
            keys.addAll(attributes.getDomains());
        }
        keys.add(appCode + "/app/" + appVer);
        keys.add(appCode + "/dbo/" + dboVer);
        keys.add(masterNode);
        keys.add("blocked:" + blocked);
        this.appStatusKey = String.valueOf(String.join(":", keys).hashCode());
    }

    @Getter
    @Setter
    public static class ApplicationStateAttributes {
        private Collection<String> domains = new ArrayList<>();
    }
}
