pluginManagement {
    plugins {
        id("biz.aQute.bnd.builder") version "7.4.0"
        // The promise toolset's own plugin: a module applying it has its
        // catalogues and citations indexed, and its tests' citations packed
        // into its jar. Its version is the framework's, named once in the
        // root build as well.
        id("cloud.jengu.promise") version "0.3.0"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://repo.jengu.cloud/repository/maven-releases/")
            content { includeGroup("cloud.jengu.promise") }
        }
    }
}

rootProject.name = "dbo"

include("dbo-bom")
include("core:dbo-core", "core:dbo-postgres", "core:dbo-fhir-common", "core:dbo-fhir-stack", "core:dbo-fhir-r4", "core:dbo-fhir-r5", "core:dbo-fhir-element", "core:dbo-fhir-packages", "core:dbo-rest", "core:dbo-auth", "core:dbo-pdi", "core:dbo-policy", "core:dbo-work", "core:dbo-asking", "core:dbo-subscriptions", "core:dbo-sync", "core:dbo-maintenance", "core:dbo-verify", "core:dbo-tenant", "core:dbo-operator", "core:dbo-fleet", "core:dbo-tenant-k8s", "core:dbo-server", "core:dbo-logging", "core:dbo-telemetry", "core:dbo-telemetry-otlp", "core:dbo-terminology", "core:dbo-definitions", "core:dbo-fhir-index", "core:dbo-fhir-validate", "core:dbo-scim", "core:dbo-promises", "core:dbo-embedded", "core:dbo-runner", "core:dbo-stream", "core:dbo-test-model", "core:dbo-step-probe", "core:harness", "core:conformance", "bench:runner")

// The Spring Boot assemblies: the runtime, hosted inside somebody else's
// application, with the container invisible from the outside.
include("assembly:spring-boot-server", "assembly:spring-boot-worker",
        "assembly:spring-boot-test")

// Applications built ON those assemblies, which is a different claim: the
// assemblies prove the wrapper works, these prove an application can be built
// on it. Their tests are the user stories, and the guide quotes them.
include("samples:spring-boot-server-app", "samples:spring-boot-worker-app",
        "samples:ward-bundle")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // The promise toolset: its own repository and release line, published
        // to the project's public Maven repository and nothing else from there.
        maven {
            url = uri("https://repo.jengu.cloud/repository/maven-releases/")
            content { includeGroup("cloud.jengu.promise") }
        }
    }
}
