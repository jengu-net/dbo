// Durable subscription delivery over the change feed (dbo#8). Personality-
// agnostic: FHIR knowledge (subscription parsing, criteria compilation) is
// injected. DBOS is the delivery engine — tenant-plane state in the tenant
// database's dbos schema (§7.4). OSGi private-embedding of DBOS is the
// packaging task; the dbo#1 spike proved it.

dependencies {
    api(project(":core:dbo-core"))
    implementation("dev.dbos:transact:1.0.0")
}
