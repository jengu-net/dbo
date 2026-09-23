plugins {
    id("biz.aQute.bnd.builder")
}

// Tenant policies (§15): audit (regular pseudonymous records riding
// the outbox, actor from the §13 token), append-only write discipline
// (engine-enforced tombstone rejection), and declarative retention (the one
// sanctioned mutation of history, itself audited). ZERO new dependencies.

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-rest"))
    // the retention sweep IS a sweep run
    api(project(":core:dbo-work"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.policy",
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
            "Import-Package" to "cloud.jengu.dbo.policy,*",
            "Export-Package" to "cloud.jengu.dbo.policy;version=0.1.0",
        ))
    }
}
