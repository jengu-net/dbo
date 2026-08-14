dependencies {
    testImplementation(project(":core:dbo-core"))
    testImplementation(project(":core:dbo-postgres"))
    testImplementation(project(":core:dbo-test-model"))
    testImplementation(project(":core:dbo-fhir-r4"))
    testImplementation(project(":core:dbo-subscriptions"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.postgresql:postgresql:42.7.11")
    testImplementation("org.apache.felix:org.apache.felix.framework:7.0.5")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    dependsOn(":core:dbo-core:jar", ":core:dbo-postgres:jar", ":core:dbo-fhir-r4:jar")
    systemProperty(
        "dbo.core.jar",
        project(":core:dbo-core").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "dbo.postgres.jar",
        project(":core:dbo-postgres").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "dbo.fhir.r4.jar",
        project(":core:dbo-fhir-r4").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed", "skipped")
    }
}
