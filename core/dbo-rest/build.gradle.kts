plugins {
    id("biz.aQute.bnd.builder")
}

// The FHIR HTTP surface: JDK HttpServer on virtual threads, ZERO new
// dependencies. Version-generic: serves any FhirStoreFacade.
// bnd computes Import-Package from bytecode.

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.rest",
            "Export-Package" to "cloud.jengu.dbo.rest;version=0.1.0",
        ))
    }
}
