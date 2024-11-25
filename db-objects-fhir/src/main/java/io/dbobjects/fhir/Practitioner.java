package io.dbobjects.fhir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dbobjects.fhir.capability.Resource;
import io.dbobjects.fhir.capability.ResourceContainer;
import io.dbobjects.fhir.types.WithUniqueId;
import lombok.Data;

@Data
@JsonTypeName("Practitioner")
public class Practitioner implements Resource, WithUniqueId<Practitioner> {
    private String id;

    private final ResourceContainer contained = new ResourceContainer(this);

}
