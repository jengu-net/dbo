plugins {
    id("biz.aQute.bnd.builder")
}

// The lane over the store's own stream: the same verbs the HTTP lane
// carries, carried instead over the durable substrate the constraints name
// (R4) — a door per tenant on the substrate, messages in and answers out,
// so one shared service needs no callback into every tenant. DBOS rides
// private in this bundle exactly as it does in dbo-subscriptions (§7.1).

val embedded: Configuration by configurations.creating
configurations.implementation.get().extendsFrom(embedded)

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-work"))
    // the verbs, encoded once: this bundle is a carrier, not a second lane
    api(project(":core:dbo-runner"))
    embedded("dev.dbos:transact:1.0.0")
    // DBOS carries Jackson 3, and 1.0.0 is the current release: its
    // next version is a milestone, so the version moves here instead.
    constraints {
        embedded("tools.jackson.core:jackson-databind:3.2.1")
    }
    compileOnly("org.slf4j:slf4j-api:2.0.18")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    bundle {
        bnd(provider {
            val jars = embedded.resolve().sortedBy { it.name }
            listOf(
                "Bundle-SymbolicName: cloud.jengu.dbo.stream",
                "Bundle-ClassPath: ." + jars.joinToString("") { ",lib/${it.name}" },
                "-includeresource: " + jars.joinToString(",") { "lib/${it.name}=${it.absolutePath}" },
                "Export-Package: cloud.jengu.dbo.stream;version=0.1.0",
                "-noimportjava: true",
                // The same filter dbo-subscriptions applies over what bnd
                // computed: the container's own packages, and none of the
                // private stack's optional reach.
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
                    "cloud.jengu.dbo.*",
                    "org.osgi.*",
                    "org.slf4j",
                    "javax.sql",
                    "com.sun.net.httpserver",
                    "!*",
                ).joinToString(","),
            ).joinToString("\n")
        })
    }
}
