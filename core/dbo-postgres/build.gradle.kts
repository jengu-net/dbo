// PostgreSQL implementation of the object engine. Depends only on dbo-core
// and javax.sql (JDK): the JDBC DataSource is provided by the host/tenant
// provisioning — this module never sees credentials
// (REQ-DBO-TEN-REGISTRY-SCOPED-ACCESS groundwork).

dependencies {
    api(project(":core:dbo-core"))
}

tasks.jar {
    manifest {
        attributes(
            "Bundle-ManifestVersion" to "2",
            "Bundle-SymbolicName" to "cloud.jengu.dbo.postgres",
            "Bundle-Version" to "0.1.0",
            "Export-Package" to "cloud.jengu.dbo.postgres;version=\"0.1.0\"",
            "Import-Package" to
                "cloud.jengu.dbo.core.api;version=\"[0.1,1)\",cloud.jengu.dbo.core.api.feed;version=\"[0.1,1)\",cloud.jengu.dbo.core;version=\"[0.1,1)\",javax.sql",
        )
    }
}
