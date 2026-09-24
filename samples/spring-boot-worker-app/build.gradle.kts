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

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
    testImplementation("org.springframework.boot:spring-boot-test:$springBootVersion")
    testImplementation("org.springframework:spring-test:7.0.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
