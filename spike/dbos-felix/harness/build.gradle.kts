dependencies {
    testImplementation(project(":api"))
    testImplementation("org.apache.felix:org.apache.felix.framework:7.0.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.postgresql:postgresql:42.7.11")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
    testImplementation("org.slf4j:slf4j-api:2.0.18")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":embedding:jar", ":contrib:jar")
    systemProperty(
        "spike.embedding.jar",
        project(":embedding").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "spike.contrib.jar",
        project(":contrib").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
