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
    implementation("org.postgresql:postgresql:42.7.13")
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.definitions",
            "Export-Package" to "cloud.jengu.dbo.definitions;version=0.1.0",
        ))
    }
}
