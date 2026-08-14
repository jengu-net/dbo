// The FHIR R4 personality: everything that knows what an R4 payload MEANS.
// HAPI rides as ordinary dependencies here; the §7.3 boundary (no HAPI type
// crosses the public API) is enforced by ApiBoundaryTest in the harness, and
// the OSGi private-embedding packaging is the spike-proven pattern applied in
// a later packaging task.

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
    api(project(":core:dbo-subscriptions"))
    api(project(":core:dbo-terminology"))
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.10.1")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-validation:8.10.1")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r4:8.10.1")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:8.10.1")
}
