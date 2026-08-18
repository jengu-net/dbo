plugins {
    id("biz.aQute.bnd.builder")
}

// Version-neutral, HAPI-free pieces shared by all FHIR personalities.
// bnd computes Import-Package from bytecode.

dependencies {
    api(project(":core:dbo-core"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.common",
            "Export-Package" to "cloud.jengu.dbo.fhir.common;version=0.1.0",
        ))
    }
}
