package io.dbobjects.storage;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ReferencedStorageObject extends StorageObject {
   Reference reference;
}
