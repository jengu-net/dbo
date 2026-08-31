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
        bnd("""
            Bundle-SymbolicName: cloud.jengu.dbo.telemetry
            Export-Package: cloud.jengu.dbo.telemetry
            -noimportjava: true
        """)
    }
}
