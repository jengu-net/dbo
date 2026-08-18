pluginManagement {
    plugins {
        id("biz.aQute.bnd.builder") version "7.1.0"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "dbo"

include("core:dbo-core", "core:dbo-postgres", "core:dbo-fhir-common", "core:dbo-fhir-r4", "core:dbo-fhir-r5", "core:dbo-rest", "core:dbo-auth", "core:dbo-pdi", "core:dbo-policy", "core:dbo-subscriptions", "core:dbo-sync", "core:dbo-maintenance", "core:dbo-tenant", "core:dbo-operator", "core:dbo-tenant-k8s", "core:dbo-server", "core:dbo-terminology", "core:dbo-test-model", "core:harness", "core:conformance")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
