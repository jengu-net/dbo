plugins {
    id("biz.aQute.bnd.builder")
}

// PostgreSQL implementation of the object engine. Depends only on dbo-core
// and javax.sql (JDK): the JDBC DataSource is provided by the host/tenant
// provisioning — this module never sees credentials
// (REQ-DBO-TEN-REGISTRY-SCOPED-ACCESS groundwork).
// bnd computes Import-Package from bytecode (dbo#13).

dependencies {
    api(project(":core:dbo-core"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.postgres",
            "Bundle-Version" to "0.1.0",
            "Export-Package" to "cloud.jengu.dbo.postgres;version=0.1.0",
        ))
    }
}
