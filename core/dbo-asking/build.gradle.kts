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
            // SUBSTITUTABLE: this bundle imports what it exports.
            //
            // Policy, not inventory — the packages are named, the rest is
            // still bnd's to compute. bnd writes an import for a bundle's own
            // export only when some OTHER package inside the bundle uses it,
            // and a bundle whose code is one package never does. Without the
            // import the bundle can only ever wire to itself, so a host that
            // supplies these classes from outside the framework is ignored:
            // the two copies then differ, and the framework HIDES a service
            // whose type the consuming bundle loads differently. Nothing is
            // logged and nothing throws; an extension point simply never
            // fires. In a container with no other provider this changes
            // nothing at all — the bundle wires to itself, as before.
            "Import-Package" to "cloud.jengu.dbo.asking,*",
            // Exported because the point of it is to be taken off the
            // whiteboard by a bundle beside this one, or by a host that
            // embedded the framework and shares Felix and the OSGi API and
            // nothing else.
            "Export-Package" to "cloud.jengu.dbo.asking;version=0.1.0",
        ))
    }
}
