plugins {
    id("biz.aQute.bnd.builder")
}

// The one logging binding the whole runtime shares.
//
// slf4j 2.x finds its provider through ServiceLoader, and slf4j-api's own
// manifest says so: it requires the osgi.serviceloader capability for
// SLF4JServiceProvider, mediated by SPI-Fly. This bundle PROVIDES that
// capability, which is what makes the lookup resolve — and what makes the
// arrangement declared rather than accidental.
//
// The alternative, and what dbo did before, was a private slf4j-simple inside
// each of five fat bundles: five bindings, five configurations, and no
// hierarchy anything could set a level on.
dependencies {
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    compileOnly("org.osgi:osgi.core:8.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.jar {
    bundle {
        bnd("""
            Bundle-SymbolicName: cloud.jengu.dbo.logging
            Bundle-Activator: cloud.jengu.dbo.logging.FrameworkLogging
            -noimportjava: true
            Provide-Capability: osgi.serviceloader;osgi.serviceloader="org.slf4j.spi.SLF4JServiceProvider"
            Require-Capability: osgi.extender;filter:="(&(osgi.extender=osgi.serviceloader.registrar)(version>=1.0.0)(!(version>=2.0.0)))"
        """)
    }
}
