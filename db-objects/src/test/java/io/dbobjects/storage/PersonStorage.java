package io.dbobjects.storage;

import io.dbobjects.jsonstorage.JsonStorage;
import io.dbobjects.testdomain.Person;

import java.util.Collection;
import java.util.Optional;

public class PersonStorage extends JsonStorage<Person> {
    public PersonStorage(String domain) {
        super(Person.class, 1, domain);
    }

    public String put(Person person) {
        return super.put(null, person);
    }

    @Override
    public StorageObjectContext buildContextFor(PayloadInfo payloadInfo) {
        return null;
    }

    public String put(String id, Person person) {
        return super.put(id, person);
    }

    public Collection<Person> selectAll() {
        return super.selectAsObjects(super.createCriteria());
    }

    public Optional<Person> getById(String id) {
        return super.getAsObject(id);
    }
}
