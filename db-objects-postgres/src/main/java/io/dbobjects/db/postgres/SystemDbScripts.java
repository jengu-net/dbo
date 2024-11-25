package io.dbobjects.db.postgres;

import io.dbobjects.DBOApplicationConfig;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.StringJoiner;

@Slf4j
@Accessors(fluent = true)
public class SystemDbScripts {

    @Getter
    private String selectDomainStateScript;
    @Getter
    private String updateNodeState;
    @Getter
    private String initApplicationState;
    @Getter
    private String syncApplicationState;
    @Getter
    private String selectApplicationState;
    @Getter
    private String syncNodeState;
    @Getter
    private String selectNodeStatuses;

    static String fmt(DBOApplicationConfig cfg, String pattern) {
        return String.format(pattern, cfg.getDefaultSchemaName());
        //            if (trim) { return result.toString().replaceAll("\\s{2,}", " ").trim();}
    }


    public static SystemDbScripts of(DBOApplicationConfig cfg) {
        var result = new SystemDbScripts();
        var domainStateTable = fmt(cfg, "%1$s.DBOBJECTS_DOMAIN_STATE");
        var nodeStateTable = fmt(cfg, "%1$s.DBO_NODE_STATE");
        var applicationStateTable = fmt(cfg, "%1$s.DBO_APPLICATION_STATE");

        var dboTypeFunc = fmt(cfg, "%1$s.dbo_type()");
        var dboVersionFunc = fmt(cfg, "%1$s.dbo_version()");

        result.selectDomainStateScript = fmt(cfg, new StringJoiner(" ")
                .add("SELECT * FROM").add(domainStateTable).add("where domain_name LIKE $1")
                .toString());

        result.updateNodeState = fmt(cfg, new StringJoiner(" ")
                .add("insert into").add(nodeStateTable)
                .add("(app_code,app_ver,dbo_ver,dbo_type,node_id,node_heartbeat,node_status_code,")
                .add("known_app_state_key,attributes)")
                .add("values ($1::VARCHAR,$2::BIGINT,$3::BIGINT,$4::VARCHAR,$5::VARCHAR,$6::BIGINT,$7::VARCHAR,$8::VARCHAR,$9::jsonb)")
                .add("on conflict(app_code, node_id) do update")
                .add("set node_heartbeat = excluded.node_heartbeat,")
                .add("node_status_code = excluded.node_status_code,")
                .add("known_app_state_key = excluded.known_app_state_key,")
                .add("attributes = excluded.attributes")
                .add("returning *;")
                .toString());

        result.initApplicationState = fmt(cfg, new StringJoiner(" ")
                .add("insert into").add(applicationStateTable)
                .add("(app_code,app_ver,master_node,blocked,app_status_key,attributes)")
                .add("values ($1::VARCHAR,$2::BIGINT,$3::VARCHAR,$4::BOOLEAN,$5::VARCHAR,$6::jsonb)")
                .add("on conflict(app_code) do update")
                .add("set app_code = excluded.app_code")
                .add("returning *,")
                .add(dboVersionFunc).add("as dbo_ver,")
                .add(dboTypeFunc).add("as dbo_type;")
                .toString());

        result.syncApplicationState = fmt(cfg, new StringJoiner(" ")
                .add("with upd as (")
                .add("update").add(applicationStateTable).add("as app_state set")
                .add("master_node = $8::VARCHAR,")
                .add("blocked = $5::BOOLEAN,")
                .add("app_status_key = $6::VARCHAR,")
                .add("attributes = $7::JSONB,")
                .add("app_ver = $3::BIGINT")
                .add("where")
                .add("app_state.master_node like $1::VARCHAR")
                .add("or app_state.master_node like ''")
                .add("or app_state.master_node is null")
                .add("or (not exists (select 1 from").add(nodeStateTable).add("as node_state")
                .add("where node_state.node_id like app_state.master_node")
                .add("and node_state.node_heartbeat > $4::BIGINT")
                .add(")) returning *)")
                .add("select *,").add(dboVersionFunc).add("as dbo_ver,").add(dboTypeFunc).add("as dbo_type, 1 as ord  from upd")
                .add("union select *,").add(dboVersionFunc).add("as dbo_ver,").add(dboTypeFunc).add("as dbo_type, 2 as ord from").add(applicationStateTable)
                .add("where app_code like $2::VARCHAR")
                .add("order by ord limit 1;")
                .toString());

        result.syncNodeState = fmt(cfg, new StringJoiner(" ")
                .add("update").add(nodeStateTable).add("set")
                .add("node_heartbeat = $2::BIGINT,")
                .add("node_status_code = $3::VARCHAR,")
                .add("known_app_state_key = $4::VARCHAR")
                .add("where")
                .add("node_id = $1::VARCHAR")
                .add("returning *;")
                .toString());

        result.selectNodeStatuses = fmt(cfg, new StringJoiner(" ")
                .add("select * from").add(nodeStateTable)
                .add("where app_code like $1::VARCHAR;")
                .toString());

        result.selectApplicationState = fmt(cfg, new StringJoiner(" ")
                .add("select *,").add(dboVersionFunc).add("as dbo_ver,").add(dboTypeFunc).add("as dbo_type")
                .add("from").add(applicationStateTable)
                .add("where app_code like $1::VARCHAR")
                .toString());

        return result;
    }
}
