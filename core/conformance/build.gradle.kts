import java.time.Duration

// The conformance harness: it drives the REST surface through the FHIR
// specification's RESTful rules and REPORTS what the server does. It is not a
// gate — dbo implements a deliberate subset (search is tier 1 by design), so a
// failure here is as often a documented boundary as a defect. The report is
// committed, so a change in what dbo conforms to shows up in a diff.
plugins {
    id("java")
}

dependencies {
    testImplementation(project(":core:dbo-core"))
    testImplementation(project(":core:dbo-postgres"))
    testImplementation(project(":core:dbo-fhir-common"))
    testImplementation(project(":core:dbo-fhir-r4"))
    testImplementation(project(":core:dbo-fhir-r5"))
    testImplementation(project(":core:dbo-rest"))
    testImplementation(project(":core:dbo-terminology"))
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The report lands in the tree, not only in build/, because its whole
    // purpose is to be read and to show movement between commits.
    systemProperty("dbo.conformance.out", rootProject.layout.projectDirectory.dir("docs/conformance").asFile.path)
    testLogging { showStandardStreams = true }
    // A conformance run that hangs tells nobody anything. Bounded, so a
    // stuck surface arrives as a failed check in the report.
    timeout.set(Duration.ofMinutes(10))
}
