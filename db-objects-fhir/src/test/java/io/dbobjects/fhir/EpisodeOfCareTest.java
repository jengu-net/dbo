package io.dbobjects.fhir;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static io.dbobjects.fhir.FhirValidator.deserialize;
import static io.dbobjects.fhir.FhirValidator.serialize;
import static io.dbobjects.fhir.FhirValidator.validate;
import static org.junit.jupiter.api.Assertions.fail;

@Slf4j
public class EpisodeOfCareTest {

    @Test
    public void testValidateMinimalMeaningfulResource() {

        var resource = new EpisodeOfCare()
                //.setId("777")
                .setStatus(EpisodeOfCare.Status.ENTERED_IN_ERROR);
        //.setActive(true);
        var patient = new Patient().setId("p1").setName("John Doe");
        var patientRef = resource.getContained().addAsLocalReference(patient);
        resource.setPatient(patientRef);
        var practitionerRef = patient.getContained().addAsLocalReference(new Practitioner().setId("pr1"));
        patient.setGeneralPractitioner(practitionerRef);
        var validationResult = validate(resource);

        // Check if the validation was successful
        if (validationResult.isSuccessful()) {
            log.info("Validation passed");
        } else {
            // Output the issues
            validationResult.getMessages().forEach(singleValidationMessage -> log.error("Issue: {}", singleValidationMessage.getMessage()));
            fail("fhir validation failed");
        }

//        log.info("s: \n{}", serialize(deserialize(EpisodeOfCare.class, serialize(resource))));
        //log.info("s: \n{}", serialize(resource));
        log.info("s: \n{}", deserialize(EpisodeOfCare.class, serialize(resource)));


        var r2 = deserialize(EpisodeOfCare.class, serialize(resource));
        //log.info("super: {}", r2.getContained().getAsLocalReference(patientRef).get());
    }


}
