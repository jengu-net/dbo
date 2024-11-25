package io.dbobjects.fhir.storage;

import io.dbobjects.fhir.HealthcareService;
import io.dbobjects.jsonstorage.JsonStorage;
import io.dbobjects.storage.SearchCriteria;
import io.dbobjects.storage.StorageObject;

import java.util.Collection;
import java.util.Optional;

public class HealthCareServiceStorage extends JsonStorage<HealthcareService> {

    public HealthCareServiceStorage(String domain) {
        super(HealthcareService.class, 1, domain);
        super.setEnvObjectBuilder(super::toMap);
    }

    public void removeById(String id) {
        super.remove(id);
    }

    public String put(HealthcareService record) {
        return super.put(record.getId(), record);
    }

    public Collection<HealthcareService> select(SearchCriteria c) {
        return super.selectAsObjects(c);
    }

    public Collection<StorageObject> selectAsStorageObjects(SearchCriteria c) {
        return super.doSelect(c);
    }

    public Optional<HealthcareService> getById(String id) {
        return super.getAsObject(id);
    }

}
