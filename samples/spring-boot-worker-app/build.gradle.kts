// A Spring Boot application that performs DBO steps, by adding one dependency.
//
// The mirror of `../spring-boot-server-app`, and the asymmetry is the point:
// that one declares tenants and serves them, this one declares none and holds
// no store. It reaches a tenant over the lane, with a credential that tenant
// issued, and the only thing it writes is the step.
//
// Not published, for the same reason as every other sample.
plugins {
    id("java")
}

val springBootVersion = rootProject.extra["dboSpringBootVersion"] as String

dependencies {
    // THE ONE DEPENDENCY. There is no store on this path and no way to get
    // one — the work vocabulary and the lane come with it, and nothing else
    // does.
    implementation(project(":assembly:spring-boot-worker"))

    implementation("org.springframework.boot:spring-boot-starter:$springBootVersion")
    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    // A deployment to perform work for. The worker's own claim cannot be made
    // without a tenant that has some — and the only honest source of one is
    // the serving application beside it, started the way it starts.
    testImplementation(project(":assembly:spring-boot-test"))
    testImplementation(project(":samples:spring-boot-server-app"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.test {
    useJUnitPlatform()
    // A tenant comes up inside this test, which expands a FHIR version out of
    // the specification. Stated here rather than inherited, per the rule that
    // a test task loading the validator says its own number.
    maxHeapSize = "3g"
}
