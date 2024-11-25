package io.dbobjects.storage;

import lombok.Data;

@Data
public class Reference {
    private String ownerId;
    private String targetId;
    private String targetType;
    private String referenceType;
}
