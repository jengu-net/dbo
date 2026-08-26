// Per-tenant SCIM 2.0 (RFC 7643/7644): the tenant authority's provisioning
// door. Users map onto the tenant's own Person/Practitioner records behind
// the membrane; the by-system enumeration a list needs stays a vault method
// inside this server and never reaches the store API or any face.

plugins {
    id("biz.aQute.bnd.builder")
}

dependencies {
    api(project(":core:dbo-core"))
    implementation(project(":core:dbo-auth"))
    implementation(project(":core:dbo-pdi"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.scim",
            "Export-Package" to "cloud.jengu.dbo.scim;version=0.1.0",
        ))
    }
}
