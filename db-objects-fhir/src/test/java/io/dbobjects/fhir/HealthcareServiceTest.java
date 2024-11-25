package io.dbobjects.fhir;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static io.dbobjects.fhir.FhirValidator.validate;

@Slf4j
public class HealthcareServiceTest {

    @Test
    //@DisplayName("Test pagination aspect within criteria based selects")
    public void testValidateMinimalValidHealthCareService() throws Exception {

        var resource = new HealthcareService()
                .setId("777")
                .setName("My Service")
                .setActive(true);
        var validationResult = validate(resource);

        // Check if the validation was successful
        if (validationResult.isSuccessful()) {
            log.info("Validation passed");
        } else {
            // Output the issues
            validationResult.getMessages().forEach(singleValidationMessage -> log.info("Issue: {}", singleValidationMessage.getMessage()));
        }
    }

}
