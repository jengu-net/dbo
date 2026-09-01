// Tenant runtime wiring: the mandatory TenantDatabaseProvisioner
// service, the default database-per-tenant implementation, and the manager
// that turns spec files into live tenant service sets. HikariCP rides
// privately in the bundle — the fat-embedding pattern.

plugins {
    id("biz.aQute.bnd.builder")
}

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
    implementation(project(":core:dbo-scim"))
    api(project(":core:dbo-policy"))
    implementation(project(":core:dbo-work"))
    // The HOST's half of the lane: Lane.inProcess and the surface a
    // tenant mounts it behind. The runner's own half — StepRunner, the
    // activator, the step services — is not installed here and does not join
    // the runtime bundle set; what the tenant uses is the interface it serves.
    implementation(project(":core:dbo-runner"))
    compileOnly("org.osgi:osgi.core:8.0.0")
    // slf4j-api is SHARED, not embedded: one binding for the whole runtime
    // instead of a private copy per bundle. HikariCP drags it in
    // transitively, so it is excluded from what rides in lib/ rather than
    // shipped and then shadowed by the import that already wires org.slf4j
    // to the shared bundle. The exclusion is on the dependency and not on
    // the configuration: configuration-level excludes are inherited through
    // extendsFrom, and would take slf4j off the compile classpath as well.
    embedded("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    // the PG driver comes from the DRIVER BUNDLE at runtime — compile-only
    compileOnly("org.postgresql:postgresql:42.7.11")
    compileOnly("org.slf4j:slf4j-api:2.0.18")
}

// bnd COMPUTES Import-Package from the bytecode of this bundle AND of what
// rides in lib/. What stays written by hand is POLICY rather than inventory:
// which packages are private, and which imports may go unresolved. The list
// of packages itself is never written down, so it cannot drift from the code
// the way a hand-kept list does.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Whose code rides in this jar, and under what terms. A recipient of
    // the artifact has the artifact, not the repository.
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    bundle {
        bnd(provider {
            val jars = embedded.resolve().sortedBy { it.name }
            listOf(
                "Bundle-SymbolicName: cloud.jengu.dbo.tenant",
                "Bundle-Activator: cloud.jengu.dbo.tenant.Activator",
                "Bundle-ClassPath: ." + jars.joinToString("") { ",lib/${it.name}" },
                "-includeresource: " + jars.joinToString(",") { "lib/${it.name}=${it.absolutePath}" },
                "Export-Package: cloud.jengu.dbo.tenant;version=0.1.0",
                // The framework delegates java.* to the boot classloader;
                // importing it is noise at best and a resolution failure at
                // worst.
                "-noimportjava: true",
                "Import-Package: " + listOf(
                    // HikariCP is private to this bundle: it rides in lib/
                    // and is reached over Bundle-ClassPath, never imported.
                    "!com.zaxxer.hikari.*",
                    // HikariCP ships adapters for metrics backends, an ORM
                    // and a bytecode library that this runtime does not
                    // carry. Their classes ride along unused; naming them
                    // here is what keeps unused code from becoming a wire
                    // the container has to satisfy.
                    "!com.codahale.metrics.*",
                    "!io.micrometer.*",
                    "!io.dropwizard.*",
                    "!io.prometheus.*",
                    "!org.hibernate.*",
                    "!javassist.*",
                    "!org.jetbrains.annotations.*",
                    // the JDK surfaces a bundle may run without
                    "javax.naming;resolution:=optional",
                    "javax.naming.spi;resolution:=optional",
                    "javax.management;resolution:=optional",
                    "javax.net.ssl;resolution:=optional",
                    "javax.crypto;resolution:=optional",
                    "javax.security.auth;resolution:=optional",
                    // everything else, computed
                    "*",
                ).joinToString(","),
            ).joinToString("\n")
        })
    }
}
