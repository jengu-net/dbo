// How an application built on these assemblies is tested, as one dependency.
//
// A test of a DBO application needs a database, a world, a tenant that came
// up and — where the application also performs work — a credential that
// tenant issued. Every test would otherwise write the same twenty lines of
// dynamic properties, and every one of them would be a chance to write a
// deployment nobody runs.
//
// THE RULE THIS MODULE KEEPS: `dbo.test.*` is the only namespace a test
// author writes. Everything the APPLICATION reads — where its database is,
// which world it serves, where a lane answers, the credential it carries — is
// derived here and contributed as a property source. A test says what it
// needs; it never says what the application needs.
//
// Published beside the wrappers, because an integrator writing an application
// on them wants this as much as this repository does.
plugins {
    id("java-library")
}

val springBootVersion = rootProject.extra["dboSpringBootVersion"] as String

dependencies {
    // The halves it configures. Both are api: a test compiles against the
    // application's own beans, and reaching them through this module would
    // be a second way to name the same types.
    api(project(":assembly:spring-boot-server"))
    api("org.springframework.boot:spring-boot-test:$springBootVersion")
    api("org.springframework:spring-test:7.0.9")
    api("org.testcontainers:testcontainers-postgresql:2.0.5")
    // The dependency-free reader the store's own checks use. A document is
    // text a test asks questions of, not an object graph it has to build —
    // building one needs a populated worker context, which is the weight this
    // repository spent item 025 removing.
    api(project(":core:dbo-fhir-validate"))
    api("org.junit.jupiter:junit-jupiter:6.0.0")

    // The worker's properties are filled in when an application has them on
    // its classpath, and not otherwise: compileOnly, so this module does not
    // put a worker into an application that did not ask for one.
    compileOnly(project(":assembly:spring-boot-worker"))


    implementation("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    compileOnly(
        "org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")
    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    // A real application to boot, and it is the sample's: an assembly proving
    // itself against an application somebody would write is the same argument
    // the server assembly's own test makes.
    testImplementation(project(":samples:spring-boot-server-app"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")

    // The packages a face is expanded from. A test's world holds face roots,
    // and a face root builds a version out of the specification — which is a
    // classpath question here rather than a container one.
    runtimeOnly(project(":core:dbo-fhir-packages"))
}

tasks.test {
    useJUnitPlatform()
    // This boots an application that brings a tenant up, which expands a whole
    // FHIR version out of the specification. The rule that a test task loading
    // the validator says its own number rather than inheriting one.
    maxHeapSize = "3g"
}
