// Tenant runtime wiring (dbo#17): the mandatory TenantDatabaseProvisioner
// service, the default database-per-tenant implementation, and the manager
// that turns spec files into live tenant service sets. HikariCP (+slf4j)
// rides privately in the bundle — the fat-embedding pattern.

val embedded: Configuration by configurations.creating
configurations.implementation.get().extendsFrom(embedded)

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
    implementation(project(":core:dbo-postgres"))
    implementation(project(":core:dbo-fhir-r4"))
    implementation(project(":core:dbo-fhir-r5"))
    implementation(project(":core:dbo-rest"))
    implementation(project(":core:dbo-auth"))
    implementation(project(":core:dbo-pdi"))
    api(project(":core:dbo-policy"))
    compileOnly("org.osgi:osgi.core:8.0.0")
    embedded("com.zaxxer:HikariCP:7.1.0")
    embedded("org.slf4j:slf4j-simple:2.0.18")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into("lib") { from(embedded) }
    doFirst {
        val libs = embedded.resolve().joinToString(",") { "lib/${it.name}" }
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "cloud.jengu.dbo.tenant",
                "Bundle-Version" to "0.1.0",
                "Bundle-Activator" to "cloud.jengu.dbo.tenant.Activator",
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to "cloud.jengu.dbo.tenant;version=\"0.1.0\"",
                "Import-Package" to listOf(
                    "org.osgi.framework",
                    "org.osgi.util.tracker",
                    "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.api.feed;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.common;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.r4;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.r5;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.postgres;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.rest;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.auth;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.pdi;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.policy;version=\"[0.1,1)\"",
                    "com.sun.net.httpserver",
                    "javax.sql",
                    "javax.naming;resolution:=optional",
                    "javax.management;resolution:=optional",
                    "javax.net.ssl;resolution:=optional",
                    "javax.crypto;resolution:=optional",
                    "javax.security.auth;resolution:=optional",
                ).joinToString(","),
            )
        }
    }
}
