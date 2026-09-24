plugins {
    id("biz.aQute.bnd.builder")
}

// The expanded form of a definition: element-per-row, located by a jsonpath
// the database can execute — the declared truth-form inversion of §6 applied
// to structures, as dbo-terminology applies it to concepts. Bulk writes go
// through the PostgreSQL driver's CopyManager, so the driver is a real
// dependency here; bnd computes Import-Package from bytecode and will demand
// org.postgresql.*, exactly right.

dependencies {
    // Where a separable domain's rows live is said in one place.
    api(project(":core:dbo-core"))
    implementation("org.postgresql:postgresql:42.7.13")
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.definitions",
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
            "Import-Package" to "cloud.jengu.dbo.definitions,*",
            "Export-Package" to "cloud.jengu.dbo.definitions;version=0.1.0",
        ))
    }
}
