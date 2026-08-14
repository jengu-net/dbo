// The normalized terminology store (dbo#9): concept-per-row, authoritative —
// the declared truth-form inversion of §6. Streaming COPY ingest needs the
// PostgreSQL driver's CopyManager, so the driver is a real dependency here
// (unlike dbo-postgres, which stays behind javax.sql).

dependencies {
    implementation("org.postgresql:postgresql:42.7.11")
}

tasks.jar {
    manifest {
        attributes(
            "Bundle-ManifestVersion" to "2",
            "Bundle-SymbolicName" to "cloud.jengu.dbo.terminology",
            "Bundle-Version" to "0.1.0",
            "Export-Package" to "cloud.jengu.dbo.terminology;version=\"0.1.0\"",
            "Import-Package" to
                "javax.sql,org.postgresql;version=\"[42,43)\",org.postgresql.copy;version=\"[42,43)\"",
        )
    }
}
