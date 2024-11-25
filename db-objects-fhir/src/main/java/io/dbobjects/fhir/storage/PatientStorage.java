package io.dbobjects.fhir.storage;

import io.dbobjects.fhir.Patient;
import io.dbobjects.jsonstorage.JsonStorage;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.SearchCriteria;
import io.dbobjects.storage.StorageObjectContext;
import io.dbobjects.storage.StorageObjectIdentifier;

import java.util.Collection;
import java.util.Optional;

public class PatientStorage extends JsonStorage<Patient> {
    public PatientStorage(String domain) {
        super(Patient.class, 1, domain);
        super.setEnvObjectBuilder(super::toMap);
    }

    public Optional<Patient> getByIdentifier(StorageObjectIdentifier identifier) {
        return super.getByIdentifier(identifier).map(super::toObject);
    }

    public Optional<Patient> getById(String id) {
        return super.getAsObject(id);
    }

    public Collection<Patient> select(SearchCriteria c) {
        return super.selectAsObjects(c);
    }

    @Override
    public StorageObjectContext buildContextFor(PayloadInfo payloadInfo) {
        var result = super.buildContextFor(payloadInfo);
        var entity = getObjectMapper().deserialize(Patient.class, payloadInfo.getPayload());
        if (entity.getIdentities() != null) {
            entity.getIdentities().forEach(e -> result.withIdentifierIfExists(e.getSystem(), e.getValue()));
        }
        return result;
    }

    public void removeById(String id) {
        super.remove(id);
    }

    public String put(Patient record) {
        return super.put(record.getId(), record);
    }
}
