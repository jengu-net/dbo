// The whiteboard contributor: registers a SpikeWorkflowsFactory. Imports only
// the api (system) and the engine's annotation package (from the embedding
// bundle). Its classes live in a foreign classloader from DBOS's perspective —
// that is the point.

dependencies {
    compileOnly(project(":api"))
    compileOnly("dev.dbos:transact:1.0.0") // annotations only; import restricted below
    compileOnly("org.osgi:osgi.core:8.0.0")
}

tasks.jar {
    manifest {
        attributes(
            "Bundle-ManifestVersion" to "2",
            "Bundle-SymbolicName" to "io.dbo.spike.contrib",
            "Bundle-Version" to "0.0.1",
            "Bundle-Activator" to "io.dbo.spike.contrib.Activator",
            "Import-Package" to "org.osgi.framework,io.dbo.spike.api,dev.dbos.transact.workflow;version=\"[1.0,2)\"",
        )
    }
}
