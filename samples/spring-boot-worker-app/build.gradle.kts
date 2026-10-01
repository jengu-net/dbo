// A Spring Boot application that performs DBO steps, by adding one dependency.
//
// The mirror of `../spring-boot-server-app`, and the asymmetry is the point:
// that one declares tenants and serves them, this one declares none and holds
// no store. It reaches a tenant over a lane, with a credential that tenant
// issued or keys whose public halves it enrolled, and the only thing it writes
// is the step.
//
// It runs two ways and its beans cannot tell which. Embedded, it is a
// dependency of the server application, and its steps arrive in that context
// through `TheWorkersSteps`. Separated, it is a JVM of its own, under the
// `edge` or the `substrate` profile. Its tests are the server application's:
// the user stories boot the two together.
//
// Not published, for the same reason as every other sample.
plugins {
    id("java")
    // So a reader can run it: `./gradlew <this>:run`. The Spring Boot Gradle
    // plugin is not used here — it adds bootJar and bootRun, and what a sample
    // needs is a main class somebody can start, not a repackaged archive.
    id("application")
}

application {
    mainClass.set("cloud.jengu.dbo.samples.worker.WorkerApplication")
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
}
