// The normalized terminology store (dbo#9): concept-per-row, authoritative —
// the declared truth-form inversion of §6. Streaming COPY ingest needs the
// PostgreSQL driver's CopyManager, so the driver is a real dependency here
// (unlike dbo-postgres, which stays behind javax.sql).

dependencies {
    implementation("org.postgresql:postgresql:42.7.11")
}
