// A bedside thermometer's driver, as the clinic's application ships it: an
// OSGi bundle of the application's own, installed into the same framework as
// the store and registering a step service there.
//
// What it can reach is part of the point. It compiles against the runner's
// step vocabulary and the OSGi core API and nothing else, so it resolves
// against the packages the application shares from the system bundle — one
// StepService class for the bundle, the runner and the application alike.
//
// Not published, and not on the clinic's classpath as classes: the clinic
// carries the jar as a resource and installs it into its framework.
plugins {
    id("biz.aQute.bnd.builder")
}

dependencies {
    compileOnly(project(":core:dbo-runner"))
    compileOnly("org.osgi:osgi.core:8.0.0")
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.samples.thermometer",
            "Bundle-Activator" to "cloud.jengu.dbo.samples.thermometer.Thermometer",
            // Nothing exported: the step service is the bundle's whole
            // contribution, and it reaches the runner as a service.
            "-exportcontents" to "",
        ))
    }
}
