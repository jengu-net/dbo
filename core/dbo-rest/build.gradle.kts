// The FHIR HTTP surface (dbo#12): JDK HttpServer on virtual threads, ZERO new
// dependencies (R2). Version-generic: serves any FhirStoreFacade. One server
// instance = one tenant store; multi-tenant dispatch belongs to the routing
// layer.

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
}

tasks.jar {
    manifest {
        attributes(
            "Bundle-ManifestVersion" to "2",
            "Bundle-SymbolicName" to "cloud.jengu.dbo.rest",
            "Bundle-Version" to "0.1.0",
            "Export-Package" to "cloud.jengu.dbo.rest;version=\"0.1.0\"",
            "Import-Package" to listOf(
                "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
                "cloud.jengu.dbo.fhir.common;version=\"[0.1,1)\"",
                "com.sun.net.httpserver",
            ).joinToString(","),
        )
    }
}
