package io.dbobjects.fhir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dbobjects.fhir.capability.Resource;
import io.dbobjects.fhir.capability.ResourceContainer;
import io.dbobjects.fhir.types.WithUniqueId;
import lombok.Data;

@Data
@JsonPropertyOrder({"resourceType", "id", "identifier"})
@JsonTypeName("HealthcareService")
public class HealthcareService implements Resource, WithUniqueId<HealthcareService> {

    /**
     * Logical id of this artifact
     */
    private String id;
    /**
     * Whether this HealthcareService record is in active use
     */
    private Boolean active;
    /**
     * Description of service as presented to a consumer while searching
     */
    private String name;

    private ResourceContainer contained;
}
