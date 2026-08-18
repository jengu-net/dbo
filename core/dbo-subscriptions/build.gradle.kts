// Durable subscription delivery over the change feed. Personality-
// agnostic: FHIR knowledge (subscription parsing, criteria compilation) is
// injected. DBOS is the delivery engine — tenant-plane state in the tenant
// database's dbos schema (§7.4). OSGi private-embedding of DBOS is the
// packaging task, and it is proven: DBOS runs inside a Felix embedding
// bundle with every dependency private (§7.1).

val embedded: Configuration by configurations.creating
configurations.implementation.get().extendsFrom(embedded)

dependencies {
    api(project(":core:dbo-core"))
    embedded("dev.dbos:transact:1.0.0")
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
                "Bundle-SymbolicName" to "cloud.jengu.dbo.subscriptions",
                "Bundle-Version" to project.version.toString().replace("-", "."),
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to "cloud.jengu.dbo.subscriptions;version=\"0.1.0\"",
                "Import-Package" to listOf(
                    "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.api.feed;version=\"[0.1,1)\"",
                    "javax.sql",
                    "javax.naming;resolution:=optional",
                    "javax.net.ssl;resolution:=optional",
                    "javax.crypto;resolution:=optional",
                    "javax.crypto.spec;resolution:=optional",
                    "javax.security.auth;resolution:=optional",
                    "javax.security.auth.callback;resolution:=optional",
                    "javax.security.auth.x500;resolution:=optional",
                    "javax.management;resolution:=optional",
                    "org.ietf.jgss;resolution:=optional",
                ).joinToString(","),
            )
        }
    }
}
