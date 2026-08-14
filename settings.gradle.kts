rootProject.name = "dbo"

include("core:dbo-core", "core:dbo-postgres", "core:dbo-fhir-r4", "core:dbo-subscriptions", "core:dbo-terminology", "core:dbo-test-model", "core:harness")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
