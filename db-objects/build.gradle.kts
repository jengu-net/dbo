plugins {
    `java-library`
}

dependencies {
    api(dbo.nanoid)
    api(mn.vertx.sql.client)
    compileOnly(mn.jackson.annotations)
    compileOnly(mn.jackson.databind)
    compileOnly(mn.logback.classic)

    testImplementation(mn.testcontainers.postgres)
    testImplementation("org.testcontainers:redpanda:1.19.4")
    testImplementation(mn.vertx.pg.client)
    testImplementation(mn.jackson.databind)
    testImplementation(mn.jackson.datatype.jsr310) // enable java.time.OffsetDateTime serializing

    testImplementation(project(":db-objects-fhir"))
    testImplementation(project(":db-objects-kafka"))
    testImplementation(project(":db-objects-postgres"))
}
