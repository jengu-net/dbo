rootProject.name = "dbo"

include("core:dbo-core", "core:dbo-postgres", "core:dbo-test-model", "core:harness")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
