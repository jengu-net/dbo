plugins {
    `java-library`
    eclipse
    idea
    alias(dbo.plugins.owasp.dependencycheck)
}

dependencies {

    // Utils
    api(mn.slf4j.api)
    annotationProcessor(mn.lombok)
    compileOnly(mn.lombok)
    testAnnotationProcessor(mn.lombok)
    testCompileOnly(mn.lombok)
    testImplementation(mn.junit.jupiter.engine)
    testRuntimeOnly(mn.logback.classic)

    api(project(":db-objects"))
    api(dbo.nanoid)
    implementation(mn.kafka.clients)

}

tasks.test {
    // Use the built-in JUnit support of Gradle.
    useJUnitPlatform()
}
