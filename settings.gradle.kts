plugins {
    id("io.micronaut.platform.catalog") version "4.4.4"
}

dependencyResolutionManagement {
    versionCatalogs {
/*
        create("mn") {
            val micronautVersion = providers.gradleProperty("micronautVersion").get()
            from("io.micronaut.platform:micronaut-platform-catalog:$micronautVersion")
        }
*/
        create("dbo") {
            library("nanoid", "com.aventrix.jnanoid", "jnanoid").version("2.0.0")
            library("netty-all", "io.netty", "netty-all").version("4.1.68.Final")
            library("liquibase-slf4j", "com.mattbertolini", "liquibase-slf4j").version("4.1.0")
            // for routing liquibase logging to slf4j
            library("liquibase-sessionlock", "com.github.blagerweij", "liquibase-sessionlock").version("1.6.4")
            // for using non-blocking locks
            plugin("owasp-dependencycheck", "org.owasp.dependencycheck").version("11.1.0")

        }
    }
}

rootProject.name = "dbo"
include("db-objects", "db-objects-fhir", "db-objects-kafka", "db-objects-postgres", "documentation")
