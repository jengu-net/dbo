plugins {
    `java-library`
    alias(dbo.plugins.owasp.dependencycheck)
}

dependencies {

    api(project(":db-objects"))
    api(dbo.nanoid)
    implementation(mn.kafka.clients)
}

tasks.test {
    // Use the built-in JUnit support of Gradle.
    useJUnitPlatform()
}
