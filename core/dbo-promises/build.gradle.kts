// The store's own promise catalogue (§16): SHAPE and PDI as the pilot.
// A leaf below the core modules on purpose — production code cites
// constants, so dbo-pdi depends on THIS, and this depends on the promise
// framework and nothing else. The catalogue knows no implementation.

plugins {
    id("biz.aQute.bnd.builder")
}

dependencies {
    api(project(":promise"))
    annotationProcessor(project(":promise"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.promises",
            "Export-Package" to "cloud.jengu.dbo.promises;version=0.1.0",
        ))
    }
}
