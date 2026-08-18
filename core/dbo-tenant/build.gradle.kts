// Tenant runtime wiring: the mandatory TenantDatabaseProvisioner
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
    implementation(project(":core:dbo-sync"))
    implementation(project(":core:dbo-auth"))
    implementation(project(":core:dbo-maintenance"))
    implementation(project(":core:dbo-pdi"))
    api(project(":core:dbo-policy"))
    compileOnly("org.osgi:osgi.core:8.0.0")
    embedded("com.zaxxer:HikariCP:7.1.0")
    // the PG driver comes from the DRIVER BUNDLE at runtime — compile-only
    compileOnly("org.postgresql:postgresql:42.7.11")
    // slf4j-api is SHARED, not embedded: one binding for the whole
    // runtime instead of a private one per bundle. compileOnly because
    // it resolves from the slf4j-api bundle at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.18")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into("lib") { from(embedded) }
    // Whose code rides in this jar, and under what terms. A recipient of
    // the artifact has the artifact, not the repository.
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    doFirst {
        val libs = embedded.resolve().joinToString(",") { "lib/${it.name}" }
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "cloud.jengu.dbo.tenant",
                "Bundle-Version" to project.version.toString().replace("-", "."),
                "Bundle-Activator" to "cloud.jengu.dbo.tenant.Activator",
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to "cloud.jengu.dbo.tenant;version=\"0.1.0\"",
                "Import-Package" to listOf(
                    "org.slf4j",
                    "org.osgi.framework",
                    "org.osgi.util.tracker",
                    "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.face;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.api.feed;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.common;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.r4;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.r5;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.postgres;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.rest;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.auth;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.pdi;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.policy;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.sync;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.maintenance;version=\"[0.1,1)\"",
                    "com.sun.net.httpserver",
                    "javax.sql",
                    "org.postgresql",
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
