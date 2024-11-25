package io.dbobjects.fhir.medications;

import lombok.Data;

/**
 * Based on https://hl7.org/fhir/medicationknowledge.html[fhir MedicationKnowledge]
 * See more in [Medication Module]
 * <p>
 * This resource supports use cases for creation of and querying for medication information including attributes such
 * as medication classifications, images of medications, costs and/or coverages, etc. This resource can be used to return
 * medication information as part of a formulary or a catalogue.
 * <p>
 * Where the `Medication` resource is intended for the simple identification of a medication for prescribing, dispensing,
 * or administering, the MedicationKnowledge resource is intended to provide more detailed information about
 * the medication. Unlike the MedicinalProductDefinition resource, the MedicationKnowledge resource is not a complete
 * definition of the medication but provides some definitional information along with formulary/catalogue-specific
 * information such as costs, monitoring programs, etc.
 */
@Data
public class MedicationKnowledge {


}
