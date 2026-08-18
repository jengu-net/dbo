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
}

tasks.jar {
    manifest {
        attributes(
            "Bundle-ManifestVersion" to "2",
            "Bundle-SymbolicName" to "cloud.jengu.dbo.logging",
            "Bundle-Version" to project.version.toString().replace("-", "."),
            "Import-Package" to "org.slf4j,org.slf4j.spi,org.slf4j.helpers,org.slf4j.event",
            "Provide-Capability" to
                "osgi.serviceloader;osgi.serviceloader=\"org.slf4j.spi.SLF4JServiceProvider\"",
            "Require-Capability" to
                "osgi.extender;filter:=\"(&(osgi.extender=osgi.serviceloader.registrar)"
                    + "(version>=1.0.0)(!(version>=2.0.0)))\"",
        )
    }
}
