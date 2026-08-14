// The FHIR HTTP surface (dbo#12): JDK HttpServer on virtual threads, ZERO new
// dependencies (R2). Version-generic: serves any FhirStoreFacade. One server
// instance = one tenant store; multi-tenant dispatch belongs to the routing
// layer.

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
}
