package io.dbobjects.storage;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * PayloadInfo defines functional part of stored payload and its metadata.
 */
@Getter
@Setter
@ToString
public class PayloadInfo {

    private String id;
    private int version;
    private String type;
    private byte[] payload;

    public static PayloadInfo of(String id, String type, int version, byte[] payload) {
        return new PayloadInfo().setId(id).setVersion(version).setType(type).setPayload(payload);
    }
}
