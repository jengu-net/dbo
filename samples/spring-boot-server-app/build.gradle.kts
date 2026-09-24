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

    // How an application on these assemblies is tested: a database for this
    // JVM, the world, a key, and a tenant that came up — all derived from
    // what `dbo.test.*` says this test needs.
    testImplementation(project(":assembly:spring-boot-test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The R5 validator loads a whole FHIR core package to build a context.
    maxHeapSize = "2g"
}
