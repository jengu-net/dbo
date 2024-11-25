package io.dbobjects.storage;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

@Getter
@Setter
@ToString
public class StorageEvent {
    private BigDecimal eventId;
    private EventType eventType;
    private PayloadInfo payloadInfo;
    private Object context;
    private Long timestamp;
    private Long processedAt;
    private String appCode;

    public StorageEvent withPayloadInfo(String id, String type, int version, byte[] payload) {
        this.setPayloadInfo(PayloadInfo.of(id, type, version, payload));
        return this;
    }

    public enum EventType {
        /**
         * Update managed domain object
         */
        U,
        /**
         * Delete managed domain object
         */
        D
    }
}
