dependencies {
    testImplementation(project(":core:dbo-core"))
    testImplementation(project(":core:dbo-postgres"))
    testImplementation(project(":core:dbo-test-model"))
    testImplementation(project(":core:dbo-fhir-r4"))
    testImplementation(project(":core:dbo-fhir-r5"))
    // the element-model face: one implementation, and R6 is its first version
    testImplementation(project(":core:dbo-fhir-element"))
    testImplementation(project(":core:dbo-rest"))
    testImplementation(project(":core:dbo-sync"))
    testImplementation(project(":core:dbo-maintenance"))
    testImplementation(project(":core:dbo-tenant"))
    testImplementation(project(":core:dbo-subscriptions"))
    testImplementation(project(":core:dbo-terminology"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":core:dbo-operator"))
    testImplementation(project(":core:dbo-tenant-k8s"))
    testImplementation(project(":core:dbo-auth"))
    testImplementation(project(":core:dbo-pdi"))
    testImplementation(project(":core:dbo-policy"))
    testImplementation(project(":core:dbo-work"))
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-k3s:2.0.5")
    testImplementation("org.postgresql:postgresql:42.7.11")
    testImplementation("org.slf4j:slf4j-api:2.0.18")
    // slf4j-api declares Require-Capability osgi.extender=osgi.serviceloader.processor.
    // SPI-Fly is that extender: a framework extension that lets a bundle's
    // ServiceLoader lookup see providers. Without it slf4j-api will not resolve.
    testImplementation("org.apache.aries.spifly:org.apache.aries.spifly.dynamic.framework.extension:1.3.7")
    testImplementation("org.apache.felix:org.apache.felix.framework:7.0.5")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

tasks.test {
    useJUnitPlatform()
    // Two versions' definitions can be resident at once now — the R4 face and
    // the R6 one are tens of megabytes of parsed StructureDefinitions each,
    // and the suite holds a container and a distribution beside them.
    maxHeapSize = "4g"
    dependsOn(":core:dbo-core:jar", ":core:dbo-postgres:jar", ":core:dbo-fhir-r4:jar")
    systemProperty(
        "dbo.core.jar",
        project(":core:dbo-core").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "dbo.postgres.jar",
        project(":core:dbo-postgres").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    dependsOn(":core:dbo-terminology:jar", ":core:dbo-fhir-r5:jar", ":core:dbo-fhir-stack:jar",
        ":core:dbo-fhir-element:jar",
        ":core:dbo-fhir-common:jar", ":core:dbo-subscriptions:jar", ":core:dbo-rest:jar",
        ":core:dbo-sync:jar", ":core:dbo-maintenance:jar", ":core:dbo-tenant:jar",
        ":core:dbo-tenant-k8s:jar", ":core:dbo-auth:jar", ":core:dbo-pdi:jar", ":core:dbo-policy:jar",
        ":core:dbo-server:installDist")
    systemProperty(
        "dbo.server.dist",
        project(":core:dbo-server").layout.buildDirectory.dir("install/dbo-server").get().asFile.absolutePath,
    )
    for ((prop, module) in mapOf(
        "dbo.fhir.common.jar" to "dbo-fhir-common",
        "dbo.fhir.stack.jar" to "dbo-fhir-stack",
        "dbo.subscriptions.jar" to "dbo-subscriptions",
        "dbo.rest.jar" to "dbo-rest",
        "dbo.sync.jar" to "dbo-sync",
        "dbo.maintenance.jar" to "dbo-maintenance",
        "dbo.tenant.jar" to "dbo-tenant",
        "dbo.tenant.k8s.jar" to "dbo-tenant-k8s",
        "dbo.auth.jar" to "dbo-auth",
        "dbo.pdi.jar" to "dbo-pdi",
        "dbo.policy.jar" to "dbo-policy",
        "dbo.fhir.element.jar" to "dbo-fhir-element",
    )) {
        systemProperty(
            prop,
            project(":core:$module").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
    doFirst {
        systemProperty("pg.driver.jar", configurations.testRuntimeClasspath.get()
            .files.first { it.name.startsWith("postgresql-") }.absolutePath)
        // slf4j-api is a BUNDLE in the runtime now, not a private jar inside
        // each module. The container has to install it for the same reason
        // the distribution ships it.
        systemProperty("slf4j.api.jar", configurations.testRuntimeClasspath.get()
            .files.first { it.name.startsWith("slf4j-api-") }.absolutePath)
        systemProperty("spifly.jar", configurations.testRuntimeClasspath.get()
            .files.first { it.name.contains("spifly") }.absolutePath)
    }
    dependsOn(":core:dbo-logging:jar")
    systemProperty(
        "dbo.logging.jar",
        project(":core:dbo-logging").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
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
        // a failed assertion's MESSAGE is the diagnosis (FeedIT names the
        // pinning transactions in it) — a bare line number is not
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
