plugins {
    id("biz.aQute.bnd.builder")
}

// In-cluster tenant provisioning seam: KubernetesSecretProvisioner
// (the mandatory provisioning service, backed by operator-written tenant Secrets)
// + SpecDirSync (tests / non-pod processes — in a pod the ConfigMap volume
// mount IS the spec directory). Fat bundle: fabric8 + Hikari ride privately
// (lib/ nested jars), only the dbo package is exported.

val embedded: Configuration by configurations.creating
configurations.implementation.get().extendsFrom(embedded)

dependencies {
    api(project(":core:dbo-tenant"))
    compileOnly("org.osgi:osgi.core:8.0.0")
    // fabric8 defaults its transport to Vert.x, which pins the whole netty
    // family and drags both into this bundle. Nothing here speaks either: the
    // JDK's own HttpClient is a supported fabric8 transport and depends on
    // nothing, so the transport is chosen rather than inherited.
    embedded("io.fabric8:kubernetes-client:7.9.0") {
        exclude(group = "io.fabric8", module = "kubernetes-httpclient-vertx")
    }
    embedded("io.fabric8:kubernetes-httpclient-jdk:7.9.0")
    embedded("com.zaxxer:HikariCP:7.1.0")
    // slf4j-api is SHARED, not embedded: one binding for the whole
    // runtime instead of a private one per bundle. compileOnly because
    // it resolves from the slf4j-api bundle at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.18")
}

// bnd COMPUTES Import-Package from this bundle's bytecode and from what
// rides in lib/. What is written here is policy — what the container
// provides, and what may go unresolved — never the list of packages.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Whose code rides in this jar, and under what terms. A recipient of
    // the artifact has the artifact, not the repository.
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    bundle {
        bnd(provider {
            val jars = embedded.resolve().sortedBy { it.name }
            listOf(
                "Bundle-SymbolicName: cloud.jengu.dbo.tenant.k8s",
                "Bundle-Activator: cloud.jengu.dbo.tenant.k8s.Activator",
                "Bundle-ClassPath: ." + jars.joinToString("") { ",lib/${it.name}" },
                "-includeresource: " + jars.joinToString(",") { "lib/${it.name}=${it.absolutePath}" },
                // NOTHING is exported, and that is the honest answer to what
                // this bundle is. Its classes are constructed by its own
                // Activator, which registers TenantDatabaseProvisioner — a
                // core.api type — as the service other bundles consume. No
                // bundle imported this package; the export existed only to
                // publish an API made of fabric8 types the bundle keeps
                // private, which no OSGi consumer could have called anyway
                // without importing a package nothing exports.
                //
                // The operator dist and the harness tests still compile
                // against these classes: they are separate JVMs loading the
                // jar from a classpath, and Export-Package governs OSGi
                // wiring, not that.
                "Private-Package: cloud.jengu.dbo.tenant.k8s",
                // And nothing leaks out sideways either: with no Export-Package
                // instruction, bnd propagates the headers of the jars riding in
                // lib/ — the first attempt published netty's shaded jctools
                // packages, at this bundle's version. A bundle that exports
                // nothing says so in one line.
                "-removeheaders: Export-Package",
                "-noimportjava: true",
                // No Declarative Services. The Kubernetes client carries DS
                // component annotations, and bnd reads them off the embedded
                // classes and asks the container for an SCR extender that dbo
                // does not run — this container wires its services from
                // activators, so the requirement is unsatisfiable by design
                // and the bundle simply never resolves.
                "-dsannotations: ",
                // A FILTER over what bnd computed, not a list of packages.
                // cloud.jengu.dbo.* is matched by pattern, so a new reference
                // to a sibling bundle is picked up on its own. The trailing
                // !* drops the private stack's reach: the Kubernetes client
                // carries adapters and codecs for a great deal this runtime
                // does not provide, and each would otherwise be a wire the
                // container has to satisfy before the bundle starts.
                "Import-Package: " + listOf(
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
                    // what the container actually provides, computed
                    "cloud.jengu.dbo.*",
                    "org.osgi.*",
                    "org.slf4j",
                    "org.postgresql",
                    "javax.sql",
                    // and nothing else
                    "!*",
                ).joinToString(","),
            ).joinToString("\n")
        })
    }
}
