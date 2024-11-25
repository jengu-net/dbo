package io.dbobjects.fhir.types;

import io.dbobjects.fhir.capability.Resource;
import lombok.Data;

@Data
public class Reference {
    private static final String LOCAL_REFERENCE_PREFIX = "#";

    private String reference;

    public static Reference of(WithUniqueId<?> resource) {
        if (resource == null || resource.getId() == null) {
            return null;
        }
        return new Reference().setReference(LOCAL_REFERENCE_PREFIX + resource.getId());
    }

    public static Reference of(Resource resource) {
        if (resource == null || resource.getId() == null) {
            return null;
        }
        return new Reference().setReference(LOCAL_REFERENCE_PREFIX + resource.getId());
    }

}
