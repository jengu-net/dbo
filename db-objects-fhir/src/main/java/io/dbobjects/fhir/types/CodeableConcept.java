package io.dbobjects.fhir.types;

import lombok.Data;

@Data
public class CodeableConcept {
    /**
     * Identity of the terminology system.
     * Based on http://tools.ietf.org/html/rfc3986.
     * For UUID (urn:uuid:53fefa32-fcbb-4ff8-8a92-55ee120877b7) use all lowercase
     */
    String system;
    /**
     * Symbol in syntax defined by the system
     */
    String code;
    /**
     * Representation defined by the system
     */
    String display;
}
