// The dbo-dbos embedding bundle: dev.dbos:transact and its transitive deps
// ride as private nested jars (Bundle-ClassPath); the only DBOS package
// exported is the workflow annotations one, which whiteboard contributors
// need for @Workflow. Everything else crosses via io.dbo.spike.api.

val embedded: Configuration by configurations.creating
configurations.compileOnly.get().extendsFrom(embedded)

dependencies {
    embedded("dev.dbos:transact:1.0.0")
    compileOnly("org.osgi:osgi.core:8.0.0")
    compileOnly(project(":api"))
}

tasks.jar {
    into("lib") { from(embedded) }
    doFirst {
        val libs = embedded.resolve().joinToString(",") { "lib/${it.name}" }
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "io.dbo.spike.embedding",
                "Bundle-Version" to "0.0.1",
                "Bundle-Activator" to "io.dbo.spike.embedding.Activator",
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to "dev.dbos.transact.workflow;version=\"1.0.0\"",
                "Import-Package" to listOf(
                    "org.osgi.framework",
                    "org.osgi.util.tracker",
                    "io.dbo.spike.api",
                    "javax.sql",
                    "javax.naming;resolution:=optional",
                    "javax.naming.spi;resolution:=optional",
                    "javax.management;resolution:=optional",
                    "javax.net;resolution:=optional",
                    "javax.net.ssl;resolution:=optional",
                    "javax.crypto;resolution:=optional",
                    "javax.crypto.spec;resolution:=optional",
                    "javax.security.auth;resolution:=optional",
                    "javax.security.auth.callback;resolution:=optional",
                    "javax.security.auth.x500;resolution:=optional",
                    "javax.security.sasl;resolution:=optional",
                    "javax.xml.parsers;resolution:=optional",
                    "javax.xml.stream;resolution:=optional",
                    "javax.xml.transform;resolution:=optional",
                    "org.xml.sax;resolution:=optional",
                    "org.w3c.dom;resolution:=optional",
                    "org.ietf.jgss;resolution:=optional",
                ).joinToString(","),
            )
        }
    }
}
