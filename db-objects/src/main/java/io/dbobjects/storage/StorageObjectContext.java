package io.dbobjects.storage;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

@Getter
public class StorageObjectContext {
    private final Collection<StorageObjectIdentifier> identifiers = new ArrayList<>();
    private final Collection<Reference> references = new ArrayList<>();
    private Map<?, ?> env;

    public StorageObjectContext withIdentifierIfExists(String system, String value) {
        if (value != null && !value.isBlank()) {
            identifiers.add(new StorageObjectIdentifier().setSystem(system).setValue(value));
        }
        return this;
    }

    public StorageObjectContext withReferences(Collection<Reference> references) {
        if (references != null) {
            this.references.addAll(references);
        }
        return this;
    }

    public StorageObjectContext withEnv(Map<?, ?> envObject) {
        this.env = envObject;
        return this;
    }
}
