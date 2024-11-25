package io.dbobjects.fhir;

import io.dbobjects.fhir.types.WithUniqueId;
import lombok.Data;

@Data
public class Encounter implements WithUniqueId<Encounter> {
    private String id;
}
