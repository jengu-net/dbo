package io.dbobjects.db.postgres;

import io.dbobjects.DBOApplicationConfig;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

@Getter
@Slf4j
@ToString
public class DomainDbScripts {

    public static final String DATA_OBJECT_ID = "id";
    public static final String DATA_OBJECT_TYPE = "object_type";
    public static final String DATA_OBJECT_VERSION = "object_version";
    public static final String DATA_OBJECT_PAYLOAD = "payload";
    public static final String DATA_LAST_EVENT_ID = "last_event_id";

    public static final String EVENT_ID = "event_id";
    public static final String EVENT_TYPE = "event_type";
    public static final String EVENT_OBJECT_ID = "object_id";
    public static final String EVENT_OBJECT_TYPE = "object_type";
    public static final String EVENT_CREATED_AT = "created_at";
    public static final String EVENT_CONTEXT = "context";
    public static final String EVENT_PAYLOAD = "payload";
    public static final String EVENT_PROCESSED_AT = "processed_at";
    public static final String EVENT_APP_CODE = "app_code";

    private String createEventScript;
    private String selectObjectsScript;
    private String selectReferencedObjectsScript;
    private String selectUnprocessedEventsScript;
    private String setEventProcessedScript;
    private String processUpdateEventScript;
    private String processDeleteEventScript;

    private String eventsTable;
    private String processedTable;
    private String dataTable;
    private String identifiersTable;
    private String referencesTable;

    private static final Map<String, DomainDbScripts> scriptsMap = new HashMap<>();

    public static DomainDbScripts of(DBOApplicationConfig cfg, String domain) {
        var result = scriptsMap.get(domain);
        if (result == null) {
            result = new DomainDbScripts();

            result.eventsTable = fmt(cfg, domain, "%1$s.DBO_%2$s_%3$s_%4$s_events");
            result.processedTable = fmt(cfg, domain, "%1$s.DBO_%2$s_%3$s_%4$s_processed");
            result.dataTable = fmt(cfg, domain, "%1$s.DBO_%2$s_%3$s_%4$s_data");
            result.identifiersTable = fmt(cfg, domain, "%1$s.DBO_%2$s_%3$s_%4$s_identifier");
            result.referencesTable = fmt(cfg, domain, "%1$s.DBO_%2$s_%3$s_%4$s_reference");

            result.createEventScript =
                    fmt(cfg, domain, new StringJoiner(" ")
                    .add("INSERT INTO").add(result.eventsTable)
                    .add("(event_type,object_id,object_type,object_version,app_code,payload)")
                    .add("VALUES")
                    .add("($1::varchar,$2::varchar,$3::varchar,$4::integer,$5::varchar,$6::bytea)")
                    .add("RETURNING *;")
                    .toString());

            result.selectUnprocessedEventsScript =
                    fmt(cfg, domain, new StringJoiner(" ")
                    .add("select de.*, dp.processed_at from")
                    .add(result.eventsTable).add("as de")
                    .add("left join").add(result.processedTable).add("as dp")
                    .add("on de.event_id = dp.event_id where dp.processed_at is null")
                    .add("order by de.event_id asc")
                    .toString());

            var dataUnqConstraintName = fmt(cfg, domain, "DBO_%2$s_%3$s_%4$s_data_unq");

            result.processUpdateEventScript =
                    fmt(cfg, domain, new StringJoiner(" ")
                    .add("INSERT INTO").add(result.dataTable)
                    .add("(last_event_id, app_code, object_type, id, object_version, payload, created_at, context)")
                    .add("VALUES")
                    .add("($1::bigint, $2::varchar, $3::varchar, $4::varchar, $5::integer, $6::bytea, $7::TIMESTAMPTZ, $8::jsonb)")
                    .add("ON CONFLICT ON CONSTRAINT").add(dataUnqConstraintName).add("DO UPDATE SET")
                    .add("last_event_id = $1::bigint, payload = $6::bytea, modified_at = $7::TIMESTAMPTZ, context = $8::jsonb")
                    .toString());

            result.processDeleteEventScript =
                    fmt(cfg, domain, new StringJoiner(" ")
                    .add("DELETE FROM").add(result.dataTable).add("AS d")
                    .add("WHERE d.id LIKE $1::varchar AND d.object_type LIKE $2::varchar;")
                    .toString());

            result.setEventProcessedScript =
                    fmt(cfg, domain, new StringJoiner(" ")
                    .add("insert into").add(result.processedTable).add("(event_id) values ($1::bigint)")
                    .add("on conflict(event_id) do nothing;")
                    .toString());

            result.selectObjectsScript =
                    fmt(cfg, domain, new StringJoiner(" ")
                    .add("SELECT * FROM").add(result.dataTable).add("WHERE 1=1")
                    .toString());

            result.selectReferencedObjectsScript = fmt(cfg, domain, new StringJoiner(" ")
                    .add("SELECT refs.owner_object_id, refs.ref_type, refs.ref_object_type, data.* FROM")
                    .add(result.dataTable).add("as data,")
                    .add(result.referencesTable).add("as refs")
                    .add("WHERE data.id = refs.ref_object_id")
                    .toString());

            scriptsMap.put(domain, result);
        }
        return result;
    }

    static String fmt(DBOApplicationConfig cfg, String domain, String pattern) {
        return String.format(pattern, cfg.getDefaultSchemaName(), cfg.getApplicationCode(),
                cfg.getApplicationVersion(), domain);
    }


}
