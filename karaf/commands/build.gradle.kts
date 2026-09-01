// The console's own commands (docs/plans/karaf-console.md). These may talk to
// the OSGi service registry and to Karaf's shell API; nothing under core/ may
// depend on them, which is why they live here and not there.
//
// Not published: this is development tooling, and it is installed into the
// console's deploy/ folder rather than resolved from a repository.

plugins {
    id("biz.aQute.bnd.builder")
}

// "commands-0.1.0-SNAPSHOT.jar" says nothing in a deploy/ folder shared with
// whatever else lands there.
base { archivesName.set("dbo-karaf-commands") }

val karafVersion = rootProject.extra["dboKarafVersion"] as String

dependencies {
    compileOnly("org.apache.karaf.shell:org.apache.karaf.shell.core:$karafVersion")
    compileOnly("org.apache.karaf.bundle:org.apache.karaf.bundle.core:$karafVersion")
    compileOnly("org.osgi:osgi.core:8.0.0")
    // JSR-353. The container already exports javax.json (Johnzon, by way of
    // Karaf), so reading one known document shape needs no parser of ours —
    // this project already carries five private copies of a minimal one.
    compileOnly("javax.json:javax.json-api:1.1.4")
    // Runs are read TYPED, from the tenant's own store service. There is no
    // HTTP surface to read them over and there should not be: the store's REST
    // is never public, and a run over a domain no face claims renders to
    // nothing on purpose. The console is where that half becomes visible.
    compileOnly(project(":core:dbo-core"))
    compileOnly(project(":core:dbo-work"))

    // The console had no tests at all, and its characteristic failure is a
    // command that registers and then cannot read anything. What is testable
    // without a container is the catalogue half, which needs no store.
    testImplementation("org.apache.karaf.shell:org.apache.karaf.shell.core:$karafVersion")
    testImplementation("org.osgi:osgi.core:8.0.0")
    testImplementation(project(":core:dbo-core"))
    testImplementation(project(":core:dbo-work"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // These tests prove a promise, so they have to be able to cite it — and
    // the processor indexes citations at THIS module's test-compile time. A
    // citation compiled without it exists, passes, and is invisible to the
    // projector, which then reads PLANNED over a proof that runs.
    testImplementation(project(":core:dbo-promises"))
    testAnnotationProcessor(project(":promise"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.karaf.commands",
            // The shell extender discovers commands by scanning the packages
            // this header names; without it the bundle starts and contributes
            // nothing, which looks exactly like a broken command.
            "Karaf-Commands" to "cloud.jengu.dbo.karaf.commands",
            // The dbo packages are OPTIONAL, and that is a startup-ordering
            // fact rather than a preference. This bundle lands in deploy/ and
            // is installed by the container before anything else; the dbo set
            // is installed by dbo-console:up, which is a command IN this
            // bundle. A hard import would leave it unresolved at startup, so
            // the command that installs the thing it needs would be the first
            // casualty — and every other dbo command with it.
            // EVERY dbo package this bundle touches has to be listed here.
            // A new one picked up by the "*" at the end resolves as MANDATORY,
            // and at startup — before dbo-console:up has installed anything —
            // the bundle then fails to resolve and contributes no commands at
            // all. The failure names a package, not a command, so it reads as
            // the console being broken rather than as one import being new.
            "Import-Package" to listOf(
                "cloud.jengu.dbo.core.api;resolution:=optional",
                "cloud.jengu.dbo.core.api.feed;resolution:=optional",
                "cloud.jengu.dbo.core.process;resolution:=optional",
                "cloud.jengu.dbo.work;resolution:=optional",
                "*",
            ).joinToString(","),
        ))
    }
}
