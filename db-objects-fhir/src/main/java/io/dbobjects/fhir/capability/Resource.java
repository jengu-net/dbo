package io.dbobjects.fhir.capability;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = Resource.RESOURCE_TYPE_FIELD_NAME)
@JsonPropertyOrder({"resourceType", "id", "identifier"})
@JsonSerialize(using = ResourceSerializer.class)
//@JsonDeserialize(using = ResourceDeserializer.class)
public interface Resource {
    String RESOURCE_TYPE_FIELD_NAME = "resourceType";
    String CONTAINED_FIELD_NAME = "contained";

    String getId();

    @JsonIgnore
        // This is handled by custom serializer
    ResourceContainer getContained();

}
