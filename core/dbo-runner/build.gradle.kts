// The embeddable step runner: registers step services, consumes their
// work over the participation seams, reports outcomes and vitals back. It
// runs wherever work runs — inside the consuming platform's container, on a
// separate machine, or in a pod scaled per step — and its activator is the
// whiteboard the runtime's own bundles never fill: with no StepService and
// no Lane registered it cycles over nothing.
//
// It also carries the HOST's half of the lane: the participation
// surface a tenant mounts, and the HTTP lane a host that is not the container
// holds instead of an in-process one. Both are Lane and its wire and nothing
// else — no store handle crosses this module's line in either direction —
// which is why they sit beside the interface they implement rather than in
// the store, where a second implementation of the protocol could grow.

plugins {
    id("biz.aQute.bnd.builder")
}

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-work"))
    // Numbers about what this runner did. api rather than implementation: a
    // bare-VM embedder constructing a runner may want to hand it an exporter,
    // and the seam is three methods.
    api(project(":core:dbo-telemetry"))
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
            "Export-Package" to "cloud.jengu.dbo.runner;version=0.1.0,cloud.jengu.dbo.runner.http;version=0.1.0",
        ))
    }
}
