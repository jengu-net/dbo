package io.dbobjects.fhir.terminology;

import io.dbobjects.fhir.types.Identifier;
import lombok.Data;

import java.util.Collection;

@Data
public class CodeSystem {
    String id;
    String url;
    String version;
    Collection<Identifier> identifier;
    String name;
    String title;
    String status;
    String description;
    Collection<CodeSystemConcept> concept;

    @Data
    public static class CodeSystemConcept {
        String code;
        String display;
        String definition;
        // TODO: Collection<designation> other possible values in case of multi-lang or other contexts (internal value etc)
        // TODO: investigate CodeSystem/concept[]/property[]
        // TODO: investigate CodeSystem/concept[]/concept[] concept tree
    }
}
