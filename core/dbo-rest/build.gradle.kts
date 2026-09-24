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
            "Import-Package" to "cloud.jengu.dbo.rest,*",
            "Export-Package" to "cloud.jengu.dbo.rest;version=0.1.0",
        ))
    }
}
