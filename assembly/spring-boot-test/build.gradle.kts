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
    // building one needs a populated worker context, which is the weight the
    // index face removed from the write path.
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

    // The packages a face is expanded from. A test's world holds face roots,
    // and a face root builds a version out of the specification — which is a
    // classpath question here rather than a container one.
    runtimeOnly(project(":core:dbo-fhir-packages"))
}

// This module's own proof: an application secured with Spring Security's
// resource server, as an application on these assemblies would secure its
// APIs, accepting its tenants' bearer tokens. Test-only, because the module
// itself must not put a security filter chain into an application that did
// not ask for one.
dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    testImplementation(
        "org.springframework.boot:spring-boot-starter-oauth2-resource-server:$springBootVersion")
    // Asking for a store is proven against both halves, and a context runner
    // hands its context over as an AssertJ provider.
    testImplementation(project(":assembly:spring-boot-worker"))
    testImplementation("org.assertj:assertj-core:3.27.7")
    // The test cites the promise it proves, and the catalogue reads that
    // citation from the index this processor writes beside the classes.
    testImplementation(project(":promise:proving"))
    testImplementation(project(":core:dbo-promises"))
    testAnnotationProcessor(project(":promise"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The serving bundle set's classloaders want more than a default heap.
    maxHeapSize = "2g"
}

publishing.publications.named<MavenPublication>("maven") {
    artifactId = "dbo-spring-boot-test"
}
