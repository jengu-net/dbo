plugins {
    id("biz.aQute.bnd.builder")
}

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
    // an exhausted delivery is a run, not a private table (#69)
    api(project(":core:dbo-work"))
    embedded("dev.dbos:transact:1.0.0")
    // slf4j-api is SHARED, not embedded: one binding for the whole
    // runtime instead of a private one per bundle. compileOnly because
    // it resolves from the slf4j-api bundle at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.18")
}

// bnd COMPUTES Import-Package from this bundle's bytecode and from what
// rides in lib/. The hand-written part is policy — what is private, what may
// go unresolved — never the list of packages itself (#32).
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Whose code rides in this jar, and under what terms. A recipient of
    // the artifact has the artifact, not the repository.
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    bundle {
        bnd(provider {
            val jars = embedded.resolve().sortedBy { it.name }
            listOf(
                "Bundle-SymbolicName: cloud.jengu.dbo.subscriptions",
                "Bundle-ClassPath: ." + jars.joinToString("") { ",lib/${it.name}" },
                "-includeresource: " + jars.joinToString(",") { "lib/${it.name}=${it.absolutePath}" },
                "Export-Package: cloud.jengu.dbo.subscriptions;version=0.1.0",
                "-noimportjava: true",
                // A FILTER over what bnd computed, not a list of packages.
                // cloud.jengu.dbo.* is matched by pattern, so a new reference
                // to a sibling bundle is picked up on its own — which is the
                // drift this issue exists to remove. What the trailing !*
                // drops is the private stack's own reach: DBOS carries
                // adapters for metrics backends, JNA, javassist and a
                // validation API that this runtime does not provide, and
                // every one of them would otherwise become a wire the
                // container has to satisfy before the bundle starts.
                "Import-Package: " + listOf(
                    "javax.naming;resolution:=optional",
                    "javax.net.ssl;resolution:=optional",
                    "javax.crypto;resolution:=optional",
                    "javax.crypto.spec;resolution:=optional",
                    "javax.security.auth;resolution:=optional",
                    "javax.security.auth.callback;resolution:=optional",
                    "javax.security.auth.x500;resolution:=optional",
                    "javax.management;resolution:=optional",
                    "org.ietf.jgss;resolution:=optional",
                    // what the container actually provides, computed
                    "cloud.jengu.dbo.*",
                    "org.osgi.*",
                    "org.slf4j",
                    "javax.sql",
                    "com.sun.net.httpserver",
                    // and nothing else
                    "!*",
                ).joinToString(","),
            ).joinToString("\n")
        })
    }
}
