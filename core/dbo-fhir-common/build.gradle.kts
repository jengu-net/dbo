// Version-neutral, HAPI-free pieces shared by all FHIR personalities.

dependencies {
    api(project(":core:dbo-core"))
}

tasks.jar {
    manifest {
        attributes(
            "Bundle-ManifestVersion" to "2",
            "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.common",
            "Bundle-Version" to "0.1.0",
            "Export-Package" to "cloud.jengu.dbo.fhir.common;version=\"0.1.0\"",
            "Import-Package" to "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
        )
    }
}
