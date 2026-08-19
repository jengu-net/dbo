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
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.karaf.commands",
            // The shell extender discovers commands by scanning the packages
            // this header names; without it the bundle starts and contributes
            // nothing, which looks exactly like a broken command.
            "Karaf-Commands" to "cloud.jengu.dbo.karaf.commands",
        ))
    }
}
