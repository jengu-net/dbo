dependencies {
    testImplementation(project(":core:dbo-core"))
    testImplementation(project(":core:dbo-promises"))
    // the promise framework's processor indexes @Proving citations at THIS
    // module's test-compile time; without this configuration the index is
    // silently absent (#140)
    testAnnotationProcessor(project(":promise"))
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

// The distribution suite is its OWN task, and the reason is a sum: the main
// executor's per-version context cache holds it near its heap ceiling for
// the whole run, and ServerDistIT boots the dist as a SEPARATE JVM that
// builds a context of its own. On the CI runner VM the two together exceed
// the machine. Two tasks run in sequence, so the big executor and the dist
// JVM never coexist — the driver task needs almost no heap, because the
// distribution does the remembering.
val distTest = tasks.register<Test>("distTest") {
    description = "The tests that boot the shipped distribution as a subprocess."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*ServerDistIT")
    maxHeapSize = "1g"
}
tasks.test {
    filter.excludeTestsMatching("*ServerDistIT")
    shouldRunAfter(distTest)
}
tasks.check { dependsOn(distTest) }

// Shared shape for BOTH suites: the wiring below (jar paths, ports, the
// container fixtures) is identical whichever executor asks.
// The heap is per task, NOT shared: the main suite holds parsed definitions
// for every version it touches, the dist driver holds nothing worth naming.
tasks.test {
    maxHeapSize = "4g"
}

// The composed promise report and the catalogue projection (#141): both run
// on the TEST runtime classpath, because that is where the catalogue
// registration and the citation index live.
val promiseReport by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Renders the composed promise report to build/reports/promise/report.md."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cloud.jengu.dbo.harness.PromiseProjection")
    args("report", layout.buildDirectory.file("reports/promise/report.md").get().asFile.absolutePath)
}

val promiseProjection by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Rewrites the generated block in docs/arc42-006-runtime/req-catalogue.md."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cloud.jengu.dbo.harness.PromiseProjection")
    args("project", rootProject.file("docs/arc42-006-runtime/req-catalogue.md").absolutePath)
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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
        ":core:dbo-tenant-k8s:jar", ":core:dbo-auth:jar", ":core:dbo-pdi:jar", ":core:dbo-scim:jar", ":core:dbo-policy:jar",
        ":promise:jar", ":core:dbo-promises:jar",
        ":core:dbo-work:jar",
        ":core:dbo-server:installDist")
    systemProperty(
        "dbo.promise.jar",
        project(":promise").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
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
        "dbo.promises.jar" to "dbo-promises",
        "dbo.scim.jar" to "dbo-scim",
        "dbo.policy.jar" to "dbo-policy",
        "dbo.work.jar" to "dbo-work",
        "dbo.fhir.element.jar" to "dbo-fhir-element",
    )) {
        systemProperty(
            prop,
            project(":core:$module").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
    // The versions the stack exports at, so a test can state the range a
    // consumer built against them would state (#47).
    systemProperty("dbo.hapi.version", rootProject.extra["hapiVersion"] as String)
    systemProperty("dbo.hl7.core.version", rootProject.extra["hl7CoreVersion"] as String)
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
