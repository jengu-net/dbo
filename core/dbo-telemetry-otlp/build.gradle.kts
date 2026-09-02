plugins {
    id("biz.aQute.bnd.builder")
}

// The one exporter: where the seam's numbers go when a deployment has a
// collector. OTLP over HTTP with the JSON encoding, rendered here and sent
// with the JDK's own client — no protocol library, so nothing rides in lib/,
// nothing transitive reaches the container, and the footprint is the seam's
// own. Found by the seam through ServiceLoader, mediated by SPI-Fly exactly
// as the logging binding is: this bundle PROVIDES the capability, the seam
// bundle consumes it, and a runtime without this bundle installed discards
// as it always did.
dependencies {
    api(project(":core:dbo-telemetry"))
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    compileOnly("org.osgi:osgi.core:8.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.jar {
    bundle {
        bnd("""
            Bundle-SymbolicName: cloud.jengu.dbo.telemetry.otlp
            Bundle-Activator: cloud.jengu.dbo.telemetry.otlp.OtlpActivator
            -noimportjava: true
            Provide-Capability: osgi.serviceloader;osgi.serviceloader="cloud.jengu.dbo.telemetry.Telemetry"
            Require-Capability: osgi.extender;filter:="(&(osgi.extender=osgi.serviceloader.registrar)(version>=1.0.0)(!(version>=2.0.0)))"
        """)
    }
}
