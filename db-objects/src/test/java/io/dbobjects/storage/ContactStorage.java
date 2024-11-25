package io.dbobjects.storage;

import io.dbobjects.jsonstorage.JsonStorage;
import io.dbobjects.testdomain.Contact;

public class ContactStorage extends JsonStorage<Contact> {
    public ContactStorage(String domain) {
        super(Contact.class, 1, domain);
    }

    @Override
    public StorageObjectContext buildContextFor(PayloadInfo payloadInfo) {
        return null;
    }
}
