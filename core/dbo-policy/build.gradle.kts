plugins {
    id("biz.aQute.bnd.builder")
}

// Tenant policies (dbo#22, §15): audit (regular pseudonymous records riding
// the outbox, actor from the §13 token), append-only write discipline
// (engine-enforced tombstone rejection), and declarative retention (the one
// sanctioned mutation of history, itself audited). ZERO new dependencies.

dependencies {
    api(project(":core:dbo-core"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.policy",
            "Export-Package" to "cloud.jengu.dbo.policy;version=0.1.0",
        ))
    }
}
