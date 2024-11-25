package io.dbobjects.fhir;

import io.dbobjects.fhir.terminology.CodeSystem;
import lombok.Data;

import java.util.Collection;

@Data
public class ValueSet {
    private String id;
    private String url;
    private String status; // PublicationStatus
    private String description;
    private ValueSetCompose compose;
    private ValueSetExpansion expansion;


    @Data
    public static class ValueSetCompose {
        private Collection<CodeSystem> include;
    }

    @Data
    public static class ValueSetExpansion {
        private String timestamp;
    }

}
