plugins {
    `java-library`
    eclipse
    idea
    alias(dbo.plugins.owasp.dependencycheck)
}

dependencies {
    api(dbo.nanoid)
    api(mn.vertx.sql.client)
    compileOnly(mn.jackson.annotations)
    compileOnly(mn.jackson.databind)
    compileOnly(mn.logback.classic)
    // Utils
    annotationProcessor(mn.lombok)
    compileOnly(mn.lombok)
    implementation(mn.slf4j.api)
    testImplementation(mn.junit.jupiter.engine)
    testRuntimeOnly(mn.logback.classic)

    testAnnotationProcessor(mn.lombok)
    testCompileOnly(mn.lombok)
    testImplementation(mn.logback.classic)
    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.testcontainers.postgres)
    testImplementation("org.testcontainers:redpanda:1.19.4")
    testImplementation(mn.vertx.pg.client)
    testImplementation(mn.jackson.databind)
    testImplementation(mn.jackson.datatype.jsr310) // enable java.time.OffsetDateTime serializing

    testImplementation(project(":db-objects-fhir"))
    testImplementation(project(":db-objects-kafka"))
    testImplementation(project(":db-objects-postgres"))
}

tasks.test {
    // Use the built-in JUnit support of Gradle.
    useJUnitPlatform()
}
