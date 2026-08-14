// The FHIR R5 personality — deliberate mechanical port of dbo-fhir-r4 with
// r5 model imports: under the §7.3 packaging model each personality embeds a
// PRIVATE HAPI stack, so per-personality compiled units are the target shape.
// A neutral-API commons (FhirTerser-based) is the known refactor option,
// deferred until the R6 ballot personality makes the cost real (dbo#10).

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r5:8.10.1")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-validation:8.10.1")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r5:8.10.1")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:8.10.1")
}
