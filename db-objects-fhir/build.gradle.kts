plugins {
    `java-library`
    eclipse
    idea
    alias(dbo.plugins.owasp.dependencycheck)
}

dependencies {
    api(project(":db-objects"))
    api(mn.jackson.annotations)
    api(mn.jackson.databind)
    implementation("eu.infomas:annotation-detector:3.0.5")

    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.jackson.databind)
    testImplementation(mn.jackson.datatype.jsr310) // enable java.time.OffsetDateTime serializing

    api(mn.vertx.sql.client)
    compileOnly(mn.logback.classic)
    // Utils
    annotationProcessor(mn.lombok)
    compileOnly(mn.lombok)
    implementation(mn.slf4j.api)

    testAnnotationProcessor(mn.lombok)
    testCompileOnly(mn.lombok)
    testImplementation(mn.testcontainers.postgres)
    testImplementation("org.testcontainers:redpanda:1.19.4")
    testImplementation(mn.vertx.pg.client)
    testImplementation(mn.jackson.databind)
    testImplementation(mn.jackson.datatype.jsr310) // enable java.time.OffsetDateTime serializing

    // for validating fhir resources
    testImplementation("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r5:6.4.3") // Use the latest version
    testImplementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r5:6.4.3") // Use the latest version
    testImplementation("ca.uhn.hapi.fhir:hapi-fhir-validation:6.4.3") // Use the latest version
    // Required to enable the FhirInstanceValidator
    testImplementation("org.apache.commons:commons-lang3:3.12.0") // Use the latest version
    testImplementation("com.fasterxml.woodstox:woodstox-core:6.4.0")
    testImplementation(mn.junit.jupiter.engine)
    testImplementation(mn.logback.classic)
    //testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    //testImplementation dbo.junit.engine


}

tasks.test {
    // Use the built-in JUnit support of Gradle.
    useJUnitPlatform()
}
