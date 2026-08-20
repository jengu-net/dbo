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
    // the retention sweep IS a sweep run (#69)
    api(project(":core:dbo-work"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.policy",
            "Export-Package" to "cloud.jengu.dbo.policy;version=0.1.0",
        ))
    }
}
