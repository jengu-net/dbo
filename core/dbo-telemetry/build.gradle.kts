plugins {
    id("biz.aQute.bnd.builder")
}

// The seam every part of the runtime reports numbers through, and nothing
// else. No exporter lives here: an exporter carries a protocol library, and
// this bundle is what everything imports — the same split the logging binding
// makes between the API everything compiles against and the provider one
// deployment installs.
//
// No dependencies, on purpose and for the same reason dbo-core has none: an
// interface with three methods and an enum is not a dependency, and a metrics
// library's API in this position would put its vocabulary in every bundle
// that ever reports a number.
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.jar {
    bundle {
        // The ServiceLoader lookup in Telemetry.installed() is mediated by
        // SPI-Fly, the way slf4j-api's is: without these headers the lookup
        // inside a bundle finds nothing and the exporter a deployment
        // installed is silently discarded — the failure that resolves and
        // dies on first use, in its quietest form. Optional, because a
        // runtime with no exporter installed must still resolve and discard.
        bnd("""
            Bundle-SymbolicName: cloud.jengu.dbo.telemetry
            Export-Package: cloud.jengu.dbo.telemetry
            -noimportjava: true
            Require-Capability: osgi.extender;filter:="(&(osgi.extender=osgi.serviceloader.processor)(version>=1.0.0)(!(version>=2.0.0)))";resolution:=optional, osgi.serviceloader;filter:="(osgi.serviceloader=cloud.jengu.dbo.telemetry.Telemetry)";resolution:=optional;cardinality:=multiple
            SPI-Consumer: *
        """)
    }
}
