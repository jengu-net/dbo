// A Spring Boot application that serves DBO tenants, by adding one dependency.
//
// The assembly beside it (`assembly/spring-boot-server`) proves the wrapper
// works; this proves an APPLICATION can be built on it, which is a different
// claim and the one an integrator is actually asking about. It shares nothing
// with `sample/` on purpose: that module is the distribution's story and the
// guide includes its source, so the two are kept apart until the guide moves.
//
// Not published. A sample is something to read and run, not something to
// depend on — the same reason `sample` and the harness are not published.
plugins {
    id("java")
}

val springBootVersion = rootProject.extra["dboSpringBootVersion"] as String

dependencies {
    // THE ONE DEPENDENCY. Everything the container needs rides with it, and
    // nothing in this module names a Bundle, a BundleContext or a framework.
    implementation(project(":assembly:spring-boot-server"))

    // The application's own half: an ordinary Spring Boot web application,
    // which is what makes the point — the port, the filter chain and the
    // beans are the application's, and the store answers inside them.
    implementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
    testImplementation("org.springframework.boot:spring-boot-test:$springBootVersion")
    testImplementation("org.springframework:spring-test:7.0.9")
    // A real servlet container, a real database, a real tenant: the claim is
    // that an application serves one, and none of it survives being mocked.
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The packages a face is expanded from. This application declares a
    // tenant that takes its face from no root, so it is the case that builds
    // one out of the specification.
    testRuntimeOnly(project(":core:dbo-fhir-packages"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The R5 validator loads a whole FHIR core package to build a context.
    maxHeapSize = "2g"
}
