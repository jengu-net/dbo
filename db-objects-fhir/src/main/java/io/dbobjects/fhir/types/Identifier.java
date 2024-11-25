package io.dbobjects.fhir.types;

import lombok.Data;

@Data
public class Identifier {
    private String system;
    private String value;
}
