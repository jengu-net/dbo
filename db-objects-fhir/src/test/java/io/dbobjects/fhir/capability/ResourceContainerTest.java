package io.dbobjects.fhir.capability;

import io.dbobjects.fhir.EpisodeOfCare;
import io.dbobjects.fhir.Patient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.dbobjects.fhir.FhirValidator.deserialize;
import static io.dbobjects.fhir.FhirValidator.serialize;
import static io.dbobjects.fhir.FhirValidator.validate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class ResourceContainerTest {

    @Test
    public void testAddContainedResourceWithMissingId() {
        var mockResource = new Resource() {
            @Override
            public String getId() {
                return "resourceId";
            }

            @Override
            public ResourceContainer getContained() {
                return null;
            }
        };

        var container = new ResourceContainer(mockResource);
        assertThrows(IllegalArgumentException.class, () -> container.add(null),
                "method add(..) must not accept null as resource");
        assertThrows(IllegalArgumentException.class, () -> container.add(new Patient()),
                "method add(..) must not accept a resource without id");
        assertThrows(IllegalArgumentException.class, () -> container.add(new Patient().setId("")),
                "method add(..) must not accept a resource without id");
        assertThrows(IllegalArgumentException.class, () -> container.add(new Patient().setId(" ")),
                "method add(..) must not accept a resource without id");
        assertDoesNotThrow(() -> container.add(new Patient().setId("1")),
                "method add(..) should accept a resource with valid id");
        assertThrows(IllegalArgumentException.class, () -> container.add(new Patient().setId("1")),
                "method add(..) must not accept a second resource with similar id");

        container.clear();
        assertThrows(IllegalArgumentException.class, () -> container.addAll(List.of(new Patient())),
                "method addAll(..) must not accept a resource without id");
        assertThrows(IllegalArgumentException.class, () -> container.addAll(List.of(new Patient().setId(""))),
                "method addAll(..) must not accept a resource without id");
        assertThrows(IllegalArgumentException.class, () -> container.addAll(List.of(new Patient().setId(" "))),
                "method addAll(..) must not accept a resource without id");
        assertDoesNotThrow(() -> container.addAll(List.of(new Patient().setId("1"), new Patient().setId("2"))),
                "method addAll(..) should accept a resource with valid id");
        assertThrows(IllegalArgumentException.class, () -> container.addAll(List.of(new Patient().setId("1"))),
                "method addAll(..) must not accept a second resource with similar id");
        assertThrows(IllegalArgumentException.class, () -> container.addAll(List.of(new Patient().setId("2"), new Patient().setId("3"))),
                "method addAll(..) must not accept a second resource with similar id");
        assertEquals(2, container.size());

        container.clear();
        assertThrows(IllegalArgumentException.class, () -> container.addAsLocalReference(null),
                "method add(..) must not accept null as resource");
        assertThrows(IllegalArgumentException.class, () -> container.addAsLocalReference(new Patient()),
                "method add(..) must not accept a resource without id");
        assertThrows(IllegalArgumentException.class, () -> container.addAsLocalReference(new Patient().setId("")),
                "method add(..) must not accept a resource without id");
        assertThrows(IllegalArgumentException.class, () -> container.addAsLocalReference(new Patient().setId(" ")),
                "method add(..) must not accept a resource without id");
        assertDoesNotThrow(() -> container.addAsLocalReference(new Patient().setId("1")),
                "method add(..) should accept a resource with valid id");
        assertThrows(IllegalArgumentException.class, () -> container.addAsLocalReference(new Patient().setId("1")),
                "method add(..) must not accept a second resource with similar id");

    }

    @Test
    @Disabled
    public void testRetrieveResourceObjectFromSerializedData() {
        var episodeOfCare = new EpisodeOfCare()
                .setStatus(EpisodeOfCare.Status.PLANNED);

        var patientRef = episodeOfCare.getContained().addAsLocalReference(new Patient().setId("777").setName("John Doe"));
        episodeOfCare.setPatient(patientRef);

        var validationResult = validate(episodeOfCare);
        assertTrue(validationResult.isSuccessful());
        var sEpisodeOfCare = deserialize(EpisodeOfCare.class, serialize(episodeOfCare));
        //sEpisodeOfCare.setStatus(EpisodeOfCare.Status.ACTIVE);
        assertEquals(episodeOfCare, sEpisodeOfCare);
        sEpisodeOfCare.getContained().addAsLocalReference(new Patient().setId("2"));
        assertNotSame(episodeOfCare, sEpisodeOfCare);
        log.info("s: \n{}", serialize(sEpisodeOfCare));

    }


}
