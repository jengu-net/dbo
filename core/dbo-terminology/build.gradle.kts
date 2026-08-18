plugins {
    id("biz.aQute.bnd.builder")
}

// The normalized terminology store: concept-per-row, authoritative —
// the declared truth-form inversion of §6. Streaming COPY ingest needs the
// PostgreSQL driver's CopyManager, so the driver is a real dependency here.
// bnd computes Import-Package from bytecode — it will demand
// org.postgresql.* from the driver bundle, exactly right.

dependencies {
    implementation("org.postgresql:postgresql:42.7.11")
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.terminology",
            "Export-Package" to "cloud.jengu.dbo.terminology;version=0.1.0",
        ))
    }
}
