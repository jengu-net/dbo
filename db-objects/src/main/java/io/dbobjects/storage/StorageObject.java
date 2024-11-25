package io.dbobjects.storage;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collection;

@Getter
@Setter
@ToString
public class StorageObject {
    private PayloadInfo payloadInfo;
    private String lastEventId;
    private Collection<ReferencedStorageObject> referencedObjects;

    public void addReferencedObject(ReferencedStorageObject referencedObject) {
        if (referencedObject != null) {
            if (referencedObjects == null) {
                referencedObjects = new ArrayList<>();
            }
            referencedObjects.add(referencedObject);
        }
    }
}
