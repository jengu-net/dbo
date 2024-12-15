plugins {
    `java-library`
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
