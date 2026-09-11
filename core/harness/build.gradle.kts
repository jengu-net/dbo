// dbo-work's OWN test module (ExecutorResolutionTest) carries @Proving
// citations of its own — the promise processor indexes them into THAT
// module's test-classes output, which a plain testImplementation on the
// main jar never pulls in. Without this line those citations exist,
// compile, and are simply invisible to this module's projector, which
// reads PLANNED where a real proof already runs (found the hard way while
// migrating the hand-written PROC rows, 2026-08-27).
evaluationDependsOn(":core:dbo-work")
val dboWorkTestOutput = project(":core:dbo-work")
        .extensions.getByType(SourceSetContainer::class.java)
        .getByName("test").output

// The element face's own tests cite too — the ballot promises about carried
// definitions and about what an author wrote reaching a reader are proven
// there and nowhere else, so without its test output on this classpath the
// projector reads PLANNED over real, passing proofs. Same trap as dbo-work
// above, same fix.
evaluationDependsOn(":core:dbo-fhir-element")
val dboFhirElementTestOutput = project(":core:dbo-fhir-element")
        .extensions.getByType(SourceSetContainer::class.java)
        .getByName("test").output

// The console's own tests cite too: the catalogue half is proven there,
// without a store, because it needs none. Same trap as the two above — the
// index lives in that module's test output, and without it on this classpath
// the projector reads PLANNED over passing proofs.
// And the tenant module's own: what a deployment declared about how many
// tenants a node brings up at once is decided without a store, so it is proven
// there. Same trap as the three above — the index lives in that module's test
// output, and a testImplementation on the main jar never pulls it in.
evaluationDependsOn(":core:dbo-tenant")
val dboTenantTestOutput = project(":core:dbo-tenant")
        .extensions.getByType(SourceSetContainer::class.java)
        .getByName("test").output

evaluationDependsOn(":karaf:commands")
val karafCommandsTestOutput = project(":karaf:commands")
        .extensions.getByType(SourceSetContainer::class.java)
        .getByName("test").output

dependencies {
    testImplementation(karafCommandsTestOutput)
    testImplementation(project(":core:dbo-core"))
    testImplementation(project(":core:dbo-promises"))
    // The reference LOCAL executor under a step service. Test-only and
    // deliberately not on dbo-runner: the participation contract names no
    // orchestrator, and a runner that compiled against one would be naming it.
    testImplementation("dev.dbos:transact:1.0.0")
    constraints {
        // as in dbo-stream: DBOS carries Jackson 3 below its advisory.
        testImplementation("tools.jackson.core:jackson-databind:3.2.1")
    }
    testImplementation(project(":core:dbo-runner"))
    testImplementation(project(":core:dbo-stream"))
    testImplementation(project(":core:dbo-telemetry-otlp"))
    // the fleet reader: proven against two runtimes in this JVM, over HTTP
    testImplementation(project(":core:dbo-fleet"))
    // the promise framework's processor indexes @Proving citations at THIS
    // module's test-compile time; without this configuration the index is
    // silently absent
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
    testImplementation(dboWorkTestOutput)
    testImplementation(dboFhirElementTestOutput)
    testImplementation(dboTenantTestOutput)
    // The console's executor half, which is the one part of it that cannot be
    // tested without a store: it reads a tenant's declarations and asks the
    // same resolution the store would. Test-only and one-directional -- no
    // main source under core/ may depend on the commands.
    testImplementation(project(":karaf:commands"))
    testImplementation("org.apache.karaf.shell:org.apache.karaf.shell.core:"
            + rootProject.extra["dboKarafVersion"])
    testImplementation("org.osgi:osgi.core:8.0.0")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-k3s:2.0.5")
    testImplementation("org.postgresql:postgresql:42.7.13")
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

// The composed promise report and the catalogue projection: both run
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

// Every bundle whose jar the ledger reads. The recorder refuses to run with
// one missing rather than recording a smaller surface, so this list and
// ApiLedger.BUNDLES have to agree — a mismatch stops the task instead of
// quietly narrowing what is guarded.
val ledgerBundles = mapOf(
    "dbo.core" to "dbo-core", "dbo.postgres" to "dbo-postgres", "dbo.auth" to "dbo-auth",
    "dbo.pdi" to "dbo-pdi", "dbo.policy" to "dbo-policy", "dbo.work" to "dbo-work",
    "dbo.runner" to "dbo-runner", "dbo.stream" to "dbo-stream", "dbo.sync" to "dbo-sync",
    "dbo.maintenance" to "dbo-maintenance", "dbo.terminology" to "dbo-terminology",
    "dbo.subscriptions" to "dbo-subscriptions", "dbo.rest" to "dbo-rest",
    "dbo.scim" to "dbo-scim", "dbo.telemetry" to "dbo-telemetry",
    "dbo.promises" to "dbo-promises", "dbo.tenant" to "dbo-tenant",
    "dbo.tenant.k8s" to "dbo-tenant-k8s", "dbo.fhir.common" to "dbo-fhir-common",
    "dbo.fhir.element" to "dbo-fhir-element", "dbo.fhir.r4" to "dbo-fhir-r4",
    "dbo.fhir.r5" to "dbo-fhir-r5",
)

// Every module whose jar IS production. The reach ledger asks whether one of
// this store's classes is named by any of them, so a module missing here reads
// as a class nothing mounts — the false alarm that gets a ratchet switched off.
// Test-only modules are absent on purpose and are the whole mechanism: a
// harness is not in anybody's jar, so a toolset only a test builds has nothing
// naming it here.
val reachModules = listOf(
    "core:dbo-core", "core:dbo-postgres", "core:dbo-auth", "core:dbo-pdi", "core:dbo-policy",
    "core:dbo-work", "core:dbo-runner", "core:dbo-stream", "core:dbo-sync",
    "core:dbo-maintenance", "core:dbo-terminology", "core:dbo-subscriptions", "core:dbo-rest",
    "core:dbo-scim", "core:dbo-telemetry", "core:dbo-telemetry-otlp", "core:dbo-promises",
    "core:dbo-tenant", "core:dbo-tenant-k8s", "core:dbo-fhir-common", "core:dbo-fhir-element",
    "core:dbo-fhir-r4", "core:dbo-fhir-r5", "core:dbo-logging", "core:dbo-verify",
    "core:dbo-operator", "core:dbo-fleet", "karaf:commands",
)

fun reachProperty(module: String) = module.replace(':', '.').replace('-', '.') + ".reach.jar"

val promiseCitations by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Re-records config/promise-citations.txt from the prose in the tree."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cloud.jengu.dbo.harness.PromiseCitations")
    systemProperty("dbo.repo.root", rootProject.projectDir.absolutePath)
    args(rootProject.file("config/promise-citations.txt").absolutePath)
}

val reachLedger by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Re-records config/reach-ledger.txt from the built production jars."
    dependsOn(tasks.named("testClasses"))
    reachModules.forEach { dependsOn(":$it:jar") }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cloud.jengu.dbo.harness.ReachLedger")
    reachModules.forEach {
        systemProperty(
            reachProperty(it),
            project(":$it").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
    args(rootProject.file("config/reach-ledger.txt").absolutePath)
}

val apiLedger by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Re-records config/api-ledger.txt from the exported packages of every bundle."
    dependsOn(tasks.named("testClasses"))
    ledgerBundles.values.forEach { dependsOn(":core:$it:jar") }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cloud.jengu.dbo.harness.ApiLedger")
    for ((prop, module) in ledgerBundles) {
        systemProperty(
            "$prop.jar",
            project(":core:$module").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
    args(rootProject.file("config/api-ledger.txt").absolutePath)
}

val promiseProjection by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Rewrites the generated block in docs/arc42-006-runtime/req-catalogue.md."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cloud.jengu.dbo.harness.PromiseProjection")
    args("project", rootProject.file("docs/arc42-006-runtime/req-catalogue.md").absolutePath,
        rootProject.file("docs/arc42-003-context/user-stories").absolutePath)
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The ledger is an INPUT, not just a file the test happens to open: without
    // this Gradle calls the task up to date after the ledger changes, and the
    // one check that would have spoken never runs.
    systemProperty("dbo.api.ledger", rootProject.file("config/api-ledger.txt").absolutePath)
    inputs.file(rootProject.file("config/api-ledger.txt"))
    // The same, for the ledger that records what production names.
    systemProperty("dbo.reach.ledger", rootProject.file("config/reach-ledger.txt").absolutePath)
    inputs.file(rootProject.file("config/reach-ledger.txt"))
    for (module in reachModules) {
        dependsOn(":$module:jar")
        systemProperty(
            reachProperty(module),
            project(":$module").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
    // Declared as an input for the same reason: a guard over a file Gradle
    // does not know about is a guard that stops running when the file changes.
    // The prose citations live in the source tree rather than in a jar, so
    // the guard needs the tree — and it needs Gradle to know the tree is an
    // input, or a renamed promise lands while this task is up to date.
    systemProperty("dbo.repo.root", rootProject.projectDir.absolutePath)
    systemProperty("dbo.promise.citations",
        rootProject.file("config/promise-citations.txt").absolutePath)
    inputs.file(rootProject.file("config/promise-citations.txt"))
    inputs.files(rootProject.fileTree(".") {
        include("**/*.java", "**/*.md", "**/*.kts")
        // The last three are written by other tasks. Declaring another task's
        // output as this one's input without saying so is an undeclared
        // dependency, and Gradle fails the build rather than let the order
        // decide the answer — which is the right call and cost a red CI to
        // learn, because nothing in a single-task run reaches that check.
        exclude("**/build/**", ".git/**", "docs/tasks/**",
                "tools/dbo-conventions/**", "CLAUDE.md",
                "docs/arc42-006-runtime/req-catalogue.md")
    }).withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty("dbo.build.workflow",
        rootProject.file(".github/workflows/build.yml").absolutePath)
    inputs.file(rootProject.file(".github/workflows/build.yml"))
    // The ledger reads the same jars the boundary tests do; these are the
    // bundles no other test had needed staged.
    for ((prop, module) in ledgerBundles) {
        dependsOn(":core:$module:jar")
        systemProperty(
            "$prop.jar",
            project(":core:$module").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
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
        ":promise:jar", ":core:dbo-promises:jar", ":core:dbo-telemetry:jar",
        ":core:dbo-work:jar", ":core:dbo-runner:jar", ":core:dbo-stream:jar",
        ":core:dbo-telemetry-otlp:jar",
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
        "dbo.telemetry.jar" to "dbo-telemetry",
        "dbo.telemetry.otlp.jar" to "dbo-telemetry-otlp",
        "dbo.scim.jar" to "dbo-scim",
        "dbo.policy.jar" to "dbo-policy",
        "dbo.work.jar" to "dbo-work",
        "dbo.runner.jar" to "dbo-runner",
        "dbo.stream.jar" to "dbo-stream",
        "dbo.fhir.element.jar" to "dbo-fhir-element",
    )) {
        systemProperty(
            prop,
            project(":core:$module").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
    // The versions the stack exports at, so a test can state the range a
    // consumer built against them would state.
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
