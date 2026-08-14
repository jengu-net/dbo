// The version-agnostic object engine API. Plain Java 21, ZERO runtime
// dependencies (REQ-DBO-CONT-FRAMEWORK-FREE-CORE) — verified by the
// verifyZeroRuntimeDeps task wired into check. The jar carries OSGi metadata
// but has no OSGi dependency.

tasks.jar {
    manifest {
        attributes(
            "Bundle-ManifestVersion" to "2",
            "Bundle-SymbolicName" to "cloud.jengu.dbo.core",
            "Bundle-Version" to "0.1.0",
            "Export-Package" to
                "cloud.jengu.dbo.core.api;version=\"0.1.0\",cloud.jengu.dbo.core.api.feed;version=\"0.1.0\",cloud.jengu.dbo.core;version=\"0.1.0\"",
        )
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
