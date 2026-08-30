// The version-agnostic object engine API. Plain Java 21, ZERO runtime
// dependencies (REQ-DBO-CONT-FRAMEWORK-FREE-CORE) — verified by the
// verifyZeroRuntimeDeps task wired into check. The jar carries OSGi metadata
// but has no OSGi dependency.

plugins {
    id("biz.aQute.bnd.builder")
}

// bnd COMPUTES Import-Package from bytecode — imports can never drift from
// code (review). Fat embedding bundles (personalities, subscriptions)
// stay hand-curated: bnd would analyze their embedded stacks into noise.
tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.core",
            // Every package the engine offers outward, named rather than
            // globbed: a package added here and not exported resolves on the
            // classpath and fails in the container, which is a failure that
            // only shows up in the tests that build one (#71).
            "Export-Package" to "cloud.jengu.dbo.core.api.*;version=0.1.0"
                + ",cloud.jengu.dbo.core.face;version=0.1.0"
                + ",cloud.jengu.dbo.core.process;version=0.1.0"
                + ",cloud.jengu.dbo.core.wire;version=0.1.0"
                + ",cloud.jengu.dbo.core;version=0.1.0",
        ))
    }
}

val verifyZeroRuntimeDeps by tasks.registering {
    val runtime = configurations.runtimeClasspath
    doLast {
        val deps = runtime.get().resolve()
        require(deps.isEmpty()) {
            "dbo-core must have zero runtime dependencies, found: ${deps.map { it.name }}"
        }
    }
}
tasks.check { dependsOn(verifyZeroRuntimeDeps) }
