pluginManagement {
    plugins {
        id("biz.aQute.bnd.builder") version "7.4.0"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "dbo"

include("dbo-bom")
include("promise")
include("core:dbo-core", "core:dbo-postgres", "core:dbo-fhir-common", "core:dbo-fhir-stack", "core:dbo-fhir-r4", "core:dbo-fhir-r5", "core:dbo-fhir-element", "core:dbo-fhir-packages", "core:dbo-rest", "core:dbo-auth", "core:dbo-pdi", "core:dbo-policy", "core:dbo-work", "core:dbo-asking", "core:dbo-subscriptions", "core:dbo-sync", "core:dbo-maintenance", "core:dbo-verify", "core:dbo-tenant", "core:dbo-operator", "core:dbo-fleet", "core:dbo-tenant-k8s", "core:dbo-server", "core:dbo-logging", "core:dbo-telemetry", "core:dbo-telemetry-otlp", "core:dbo-terminology", "core:dbo-definitions", "core:dbo-fhir-index", "core:dbo-fhir-validate", "core:dbo-scim", "core:dbo-promises", "core:dbo-proving", "core:dbo-runner", "core:dbo-stream", "core:dbo-test-model", "core:dbo-step-probe", "core:harness", "core:conformance", "guide", "bench:runner", "sample", "sample:participant")

// The development console is DETACHED from the build while the runtime moves
// to Java 25: Karaf 4.4.11 closes the 4.4 line and runs on an older JVM, so
// its bundles would have to be held back to 21 bytecode on their own while
// everything around them compiles to 25. The tree under karaf/ is left in
// place — re-including these three projects is what re-attaches it, and the
// harness wiring and console test it carried were removed with it.
// include("karaf", "karaf:commands", "karaf:slf4j-compat")

// The Spring Boot assemblies: the runtime, hosted inside somebody else's
// application, with the container invisible from the outside.
include("assembly:spring-boot-core", "assembly:spring-boot-server", "assembly:spring-boot-worker",
        "assembly:spring-boot-test")

// Applications built ON those assemblies, which is a different claim: the
// assemblies prove the wrapper works, these prove an application can be built
// on it. Nothing here shares anything with `sample`, which is the
// distribution's story and the one the guide includes the source of.
include("samples:spring-boot-server-app", "samples:spring-boot-worker-app")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
