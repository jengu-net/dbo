package io.dbobjects.testdomain;

import io.dbobjects.jsonstorage.JsonStorage;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.StorageObjectContext;
import lombok.NonNull;

public class PersonStorage extends JsonStorage<Person> {
    public PersonStorage(@NonNull String domainName) {
        super(Person.class, 1, domainName);
    }

    @Override
    public StorageObjectContext buildContextFor(PayloadInfo payloadInfo) {
        return null;
    }
}
