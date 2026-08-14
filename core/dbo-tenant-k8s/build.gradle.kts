// In-cluster tenant provisioning seam (dbo#19): KubernetesSecretProvisioner
// (the dbo#17 mandatory service, backed by operator-written tenant Secrets)
// + SpecDirSync (tests / non-pod processes — in a pod the ConfigMap volume
// mount IS the spec directory). Fat bundle: fabric8 + Hikari ride privately
// (lib/ nested jars), only the dbo package is exported.

val embedded: Configuration by configurations.creating
configurations.implementation.get().extendsFrom(embedded)

dependencies {
    api(project(":core:dbo-tenant"))
    compileOnly("org.osgi:osgi.core:8.0.0")
    embedded("io.fabric8:kubernetes-client:7.3.1")
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
                "Bundle-SymbolicName" to "cloud.jengu.dbo.tenant.k8s",
                "Bundle-Version" to "0.1.0",
                "Bundle-Activator" to "cloud.jengu.dbo.tenant.k8s.Activator",
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to "cloud.jengu.dbo.tenant.k8s;version=\"0.1.0\"",
                "Import-Package" to listOf(
                    "org.osgi.framework",
                    "cloud.jengu.dbo.tenant;version=\"[0.1,1)\"",
                    "org.postgresql",
                    "javax.sql",
                    "javax.naming;resolution:=optional",
                    "javax.net.ssl;resolution:=optional",
                    "javax.crypto;resolution:=optional",
                    "javax.crypto.spec;resolution:=optional",
                    "javax.security.auth;resolution:=optional",
                    "javax.security.auth.callback;resolution:=optional",
                    "javax.security.auth.x500;resolution:=optional",
                    "javax.security.cert;resolution:=optional",
                    "javax.management;resolution:=optional",
                    "javax.annotation;resolution:=optional",
                    "javax.xml.stream;resolution:=optional",
                    "javax.xml.parsers;resolution:=optional",
                    "javax.xml.namespace;resolution:=optional",
                    "org.xml.sax;resolution:=optional",
                    "org.w3c.dom;resolution:=optional",
                    "org.ietf.jgss;resolution:=optional",
                    "sun.misc;resolution:=optional",
                    "sun.nio.ch;resolution:=optional",
                    "sun.security.x509;resolution:=optional",
                    "jdk.net;resolution:=optional",
                ).joinToString(","),
            )
        }
    }
}
