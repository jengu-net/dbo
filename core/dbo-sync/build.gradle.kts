plugins {
    id("biz.aQute.bnd.builder")
}

// Declared content dependencies over the feed (§6): the Project.link[]
// replacement — read-only, provenance-tagged, converted-at-apply copies
// streamed into the dependent tenant's own database.
// bnd computes Import-Package from bytecode.

dependencies {
    api(project(":core:dbo-core"))
    // applying a declaration and streaming a dependency are both sweeps (#73)
    api(project(":core:dbo-work"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.sync",
            "Export-Package" to "cloud.jengu.dbo.sync;version=0.1.0",
        ))
    }
}
