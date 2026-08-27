// The embeddable step runner (#79): registers step services, consumes their
// work over the participation seams, reports outcomes and vitals back. A
// CLIENT library, not a store component — it does not join the runtime
// bundle set, nothing in the tenant runtime imports it, and it runs wherever
// work runs: inside the consuming platform's container, on a separate
// machine, or in a pod scaled per step.

plugins {
    id("biz.aQute.bnd.builder")
}

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-work"))
    // Injectable into an EXISTING container: the activator whiteboard-tracks
    // step services from any bundle. compileOnly, like every bundle here —
    // outside OSGi the activator is simply never called.
    compileOnly("org.osgi:osgi.core:8.0.0")
    // slf4j-api is SHARED, not embedded — the host container (or the
    // embedder's classpath) provides the one binding, same as every bundle
    // here. api rather than compileOnly: a bare-VM embedder gets a working
    // logger without knowing our conventions.
    api("org.slf4j:slf4j-api:2.0.18")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.runner",
            "Bundle-Activator" to "cloud.jengu.dbo.runner.Activator",
            "Export-Package" to "cloud.jengu.dbo.runner;version=0.1.0",
        ))
    }
}
