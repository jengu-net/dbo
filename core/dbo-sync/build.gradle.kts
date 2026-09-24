plugins {
    id("biz.aQute.bnd.builder")
}

// Declared content dependencies over the feed (§6): the Project.link[]
// replacement — read-only, provenance-tagged, converted-at-apply copies
// streamed into the dependent tenant's own database.
// bnd computes Import-Package from bytecode.

dependencies {
    api(project(":core:dbo-core"))
    // applying a declaration and streaming a dependency are both sweeps
    api(project(":core:dbo-work"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.sync",
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
            "Import-Package" to "cloud.jengu.dbo.sync.http,*",
            "Export-Package" to "cloud.jengu.dbo.sync;version=0.1.0,cloud.jengu.dbo.sync.http;version=0.1.0",
        ))
    }
}
