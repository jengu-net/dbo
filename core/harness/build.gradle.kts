dependencies {
    testImplementation(project(":core:dbo-core"))
    testImplementation(project(":core:dbo-postgres"))
    testImplementation(project(":core:dbo-test-model"))
    testImplementation(project(":core:dbo-fhir-r4"))
    testImplementation(project(":core:dbo-fhir-r5"))
    testImplementation(project(":core:dbo-rest"))
    testImplementation(project(":core:dbo-sync"))
    testImplementation(project(":core:dbo-maintenance"))
    testImplementation(project(":core:dbo-tenant"))
    testImplementation(project(":core:dbo-subscriptions"))
    testImplementation(project(":core:dbo-terminology"))
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
    dependsOn(":core:dbo-terminology:jar", ":core:dbo-fhir-r5:jar",
        ":core:dbo-fhir-common:jar", ":core:dbo-subscriptions:jar", ":core:dbo-rest:jar",
        ":core:dbo-sync:jar", ":core:dbo-maintenance:jar", ":core:dbo-tenant:jar")
    for ((prop, module) in mapOf(
        "dbo.fhir.common.jar" to "dbo-fhir-common",
        "dbo.subscriptions.jar" to "dbo-subscriptions",
        "dbo.rest.jar" to "dbo-rest",
        "dbo.sync.jar" to "dbo-sync",
        "dbo.maintenance.jar" to "dbo-maintenance",
        "dbo.tenant.jar" to "dbo-tenant",
    )) {
        systemProperty(
            prop,
            project(":core:$module").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
    doFirst {
        systemProperty("pg.driver.jar", configurations.testRuntimeClasspath.get()
            .files.first { it.name.startsWith("postgresql-") }.absolutePath)
    }
    systemProperty(
        "dbo.fhir.r5.jar",
        project(":core:dbo-fhir-r5").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "dbo.terminology.jar",
        project(":core:dbo-terminology").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "dbo.fhir.r4.jar",
        project(":core:dbo-fhir-r4").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed", "skipped")
    }
}
