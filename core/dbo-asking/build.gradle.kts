plugins {
    id("biz.aQute.bnd.builder")
}

// The questions a product asks a tenant: what needs somebody, what is on the
// screen, who read this record.
//
// Its own module because of what it answers about. It began beside the runs
// it asked about, and once it answered about records and the trail as well,
// living in `dbo-work` meant a vocabulary named after one of the three things
// it covers. What it needs is the engine's own reads and the run vocabulary,
// which is exactly these two dependencies and nothing else.

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-work"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.asking",
            // Exported because the point of it is to be taken off the
            // whiteboard by a bundle beside this one, or by a host that
            // embedded the framework and shares Felix and the OSGi API and
            // nothing else.
            "Export-Package" to "cloud.jengu.dbo.asking;version=0.1.0",
        ))
    }
}
