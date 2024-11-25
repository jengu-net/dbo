package io.dbobjects.fhir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dbobjects.fhir.capability.Resource;
import io.dbobjects.fhir.capability.ResourceContainer;
import io.dbobjects.fhir.types.Reference;
import io.dbobjects.fhir.types.WithUniqueId;
import io.dbobjects.storage.StorageObjectIdentifier;
import lombok.Data;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Data
@JsonPropertyOrder({"resourceType", "id", "identifier"})
@JsonTypeName("Patient")
public class Patient implements Resource, WithUniqueId<Patient> {

    public static final String ID_SYSTEM_ESTONIAN_ID = "eid";
    public static final Collection<String> TRUSTED_IDENTITY_SYSTEMS = Collections.unmodifiableList(List.of(ID_SYSTEM_ESTONIAN_ID));

    private String id;
    private String name;
    private Collection<StorageObjectIdentifier> identities;

    private final ResourceContainer contained = new ResourceContainer(this);

    private Reference generalPractitioner;

    public boolean isIdentified() {
        return identities != null && identities.stream().anyMatch(i -> TRUSTED_IDENTITY_SYSTEMS.contains(i.getSystem()));
    }

}
