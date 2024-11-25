package io.dbobjects.fhir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.dbobjects.fhir.capability.Resource;
import io.dbobjects.fhir.capability.ResourceContainer;
import io.dbobjects.fhir.types.Reference;
import io.dbobjects.fhir.types.WithUniqueId;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * <p>
 * An association between a patient and an organization / healthcare provider(s) during which time encounters may occur.
 * The managing organization assumes a level of responsibility for the patient during this time.
 * </p>
 * <p>
 * The EpisodeOfCare Resource contains information about an association of a Patient with a Healthcare Provider for a
 * period of time under which related healthcare activities may occur.
 * </p>
 * <p>
 * In many cases, this represents a period of time where the Healthcare Provider has some level of responsibility for
 * the care of the patient regarding a specific condition or problem, even if not currently participating in an encounter.
 * </p>
 * <a href="https://hl7.org/fhir/episodeofcare.html">fhir ref.</a>
 */
@Data
@JsonPropertyOrder({"resourceType", "id", "identifier"})
@JsonTypeName("EpisodeOfCare")
public class EpisodeOfCare implements Resource, WithUniqueId<EpisodeOfCare> {

    private String id;
    private Status status;
    private boolean active;
    private Reference patient;

    private final ResourceContainer contained = new ResourceContainer(this);

    @RequiredArgsConstructor
    public enum Status {
        PLANNED("planned"),
        WAIT_LIST("waitlist"),
        ACTIVE("active"),
        ON_HOLD("onhold"),
        FINISHED("finished"),
        CANCELLED("cancelled"),
        ENTERED_IN_ERROR("entered-in-error");

        @JsonValue
        private final String value;
    }
}
