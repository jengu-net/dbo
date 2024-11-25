package io.dbobjects.db.postgres;

import io.dbobjects.ApplicationState;
import io.dbobjects.ObjectMapper;
import io.dbobjects.nodesync.SyncedNodeState;
import io.dbobjects.parallel.NodeState;
import io.dbobjects.storage.Reference;
import io.dbobjects.storage.ReferencedStorageObject;
import io.dbobjects.storage.StorageEvent;
import io.dbobjects.storage.StorageObject;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Row;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.function.Supplier;

import static io.dbobjects.db.postgres.DomainDbScripts.DATA_OBJECT_ID;
import static io.dbobjects.db.postgres.DomainDbScripts.DATA_OBJECT_PAYLOAD;
import static io.dbobjects.db.postgres.DomainDbScripts.DATA_OBJECT_TYPE;
import static io.dbobjects.db.postgres.DomainDbScripts.DATA_OBJECT_VERSION;
import static io.dbobjects.storage.PayloadInfo.of;

@RequiredArgsConstructor
public class Mappers {

    private final ObjectMapper objectMapper;


    public Row mapRow(Row row) {
        return row;
    }

    public ApplicationState mapApplicationState(Row row) {
        return new ApplicationState()
                .setAppCode(row.getString("app_code"))
                .setAppVer(row.getInteger("app_ver"))
                .setDboVer(row.getInteger("dbo_ver"))
                .setDbType(row.getString("dbo_type"))
                .setMasterNode(row.getString("master_node"))
                .setBlocked(row.getBoolean("blocked"))
                .setAppStatusKey(row.getString("app_status_key"))
                .setAttributes(fromJsonString(row.getValue("attributes").toString(),
                        ApplicationState.ApplicationStateAttributes.class));
    }

    public SyncedNodeState mapNodeState(Row row) {
        return new SyncedNodeState()
                .setAppCode(row.getString("app_code"))
                .setAppVersion(row.getInteger("app_ver"))
                .setDboVersion(row.getInteger("dbo_ver"))
                .setDbType(row.getString("dbo_type"))
                .setNodeId(row.getString("node_id"))
                .setNodeHeartbeat(row.getLong("node_heartbeat"))
                .setKnownAppStateKey(row.getString("known_app_state_key"))
                .setNodeStatusCode(
                        NodeState.StatusCode.valueOf(row.getString("node_status_code")));
    }

    public StorageEvent mapDomainEvent(Row row) {
        return new StorageEvent()
                .setEventId(row.getBigDecimal("event_id"))
                .setEventType(StorageEvent.EventType.valueOf(row.getString("event_type")))
                .withPayloadInfo(
                        row.getString("object_id"),
                        row.getString("object_type"),
                        row.getInteger("object_version"),
                        parseBuffer(row, "payload")
                );
    }

    public ReferencedStorageObject mapReferencedStorageObject(Row row) {
        var result = new ReferencedStorageObject();
        mapToDomainObject(() -> result, row);
        return result.setReference(new Reference()
                        .setOwnerId(row.getString("owner_object_id"))
                        .setTargetId(result.getPayloadInfo().getId())
                        .setTargetType(result.getPayloadInfo().getType())
                        .setReferenceType(row.getString("ref_type")));
    }

    public StorageObject mapToDomainObject(Row rs) {
        return mapToDomainObject(StorageObject::new, rs);
    }

    private StorageObject mapToDomainObject(Supplier<StorageObject> builder, Row rs) {
        return builder.get()
                .setPayloadInfo(
                        of(rs.getString(DATA_OBJECT_ID), rs.getString(DATA_OBJECT_TYPE),
                                rs.getInteger(DATA_OBJECT_VERSION),
                                Mappers.parseBuffer(rs, DATA_OBJECT_PAYLOAD))
                );
    }

    private <C> C fromJsonString(String entityString, Class<C> clazz) {
        return entityString == null || entityString.trim().length() == 0 ? null :
                objectMapper.deserialize(clazz, entityString);
    }

    public static byte[] parseBuffer(Row row, String rowName) {
        return Optional.ofNullable(row.getBuffer(rowName))
                .map(Buffer::getBytes).orElse(null);

        //return Optional.ofNullable(row.getBuffer(rowName))
        //        .map(b -> new String(b.getBytes(), StandardCharsets.UTF_8)).orElse(null);

    }

}
