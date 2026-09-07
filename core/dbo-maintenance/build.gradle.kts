plugins {
    id("biz.aQute.bnd.builder")
}

// Backup is export, restore is import (§11). The PG driver supplies
// COPY for the byte-faithful fidelity element; crypto is JDK-only.
// bnd computes Import-Package from bytecode.

dependencies {
    api(project(":core:dbo-core"))
    implementation("org.postgresql:postgresql:42.7.13")
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.maintenance",
            "Export-Package" to "cloud.jengu.dbo.maintenance;version=0.1.0",
        ))
    }
}
