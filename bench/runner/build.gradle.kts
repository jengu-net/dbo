// The bench runner. It runs ON the Pi, against the production serving path
// over loopback — putting the network in the measurement loop would make this
// a test of the LAN rather than of the store.
plugins {
    id("application")
}

application {
    mainClass.set("cloud.jengu.dbo.bench.Bench")
}

dependencies {
    implementation(project(":core:dbo-core"))
    implementation(project(":core:dbo-postgres"))
    implementation(project(":core:dbo-fhir-common"))
    implementation(project(":core:dbo-fhir-r4"))
    implementation(project(":core:dbo-rest"))
    implementation("org.postgresql:postgresql:42.7.13")
    // The same pool production uses. Without it PGSimpleDataSource opens a
    // connection per call and the benchmark measures connection setup.
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation(project(":core:dbo-telemetry"))
    // On the RUNTIME classpath only: the runner asks Telemetry for whatever
    // this deployment installed, and an exporter with no endpoint configured
    // is inert. So a run reports where it is told to and stays silent where
    // it is not, without the runner knowing which.
    runtimeOnly(project(":core:dbo-telemetry-otlp"))
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
}
