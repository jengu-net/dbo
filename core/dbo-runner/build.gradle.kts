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
    // @Proving citations only — dbo-promises depends on :promise alone, so
    // this does not create a cycle back into dbo-runner.
    testImplementation(project(":core:dbo-promises"))
    testAnnotationProcessor(project(":promise"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.runner",
            // SUBSTITUTABLE: this bundle imports what it exports.
            //
            // Policy, not inventory — the packages are named, the rest is
            // still bnd's to compute. bnd writes an import for a bundle's own
            // export only when some OTHER package inside the bundle uses it,
            // and a bundle whose code is one package never does. Without the
            // import the bundle can only ever wire to itself, so a host that
            // supplies these classes from outside the framework is ignored:
            // the two copies then differ, and the framework HIDES a service
            // whose type the consuming bundle loads differently. Nothing is
            // logged and nothing throws; an extension point simply never
            // fires. In a container with no other provider this changes
            // nothing at all — the bundle wires to itself, as before.
            "Import-Package" to "cloud.jengu.dbo.runner.http,*",
            "Bundle-Activator" to "cloud.jengu.dbo.runner.Activator",
            "Export-Package" to "cloud.jengu.dbo.runner;version=0.1.0,cloud.jengu.dbo.runner.http;version=0.1.0",
        ))
    }
}
