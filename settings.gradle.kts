pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    val micronautPluginVersion = "4.4.4"
    val owaspDependencyCheckVersion = "11.1.0"
    plugins {
        id("io.micronaut.platform.catalog") version(micronautPluginVersion)
        id("io.micronaut.library") version(micronautPluginVersion)
        id("io.micronaut.application") version(micronautPluginVersion)
        id("io.micronaut.test-resources") version(micronautPluginVersion)

        id("org.owasp.dependencycheck") version(owaspDependencyCheckVersion)
    }
}

plugins {
    id("io.micronaut.platform.catalog")
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    versionCatalogs {
        create("dbo") {
            library("nanoid", "com.aventrix.jnanoid", "jnanoid").version("2.0.0")
            library("netty-all", "io.netty", "netty-all").version("4.1.68.Final")
            library("liquibase-slf4j", "com.mattbertolini", "liquibase-slf4j").version("4.1.0")
            // for routing liquibase logging to slf4j
            library("liquibase-sessionlock", "com.github.blagerweij", "liquibase-sessionlock").version("1.6.4")
            // for using non-blocking locks
            //plugin("owasp-dependencycheck", "org.owasp.dependencycheck")
        }
    }
}

rootProject.name = "dbo"
include("db-objects",
    "db-objects-fhir",
    "db-objects-kafka",
    "db-objects-postgres",
    "db-objects-micronaut",
    "db-objects-micronaut-petclinic",
    "documentation")
