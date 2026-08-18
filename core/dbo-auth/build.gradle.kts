plugins {
    id("biz.aQute.bnd.builder")
}

// The tenant authority (§13): per-tenant OIDC issuer — identity
// artifacts as records, JDK-crypto JWS/JWK, SMART system-scope grammar,
// client_credentials token endpoint, and the surface guard. ZERO new
// dependencies: crypto is java.security/javax.crypto, HTTP is the JDK
// server already serving the store surface.

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-rest"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.auth",
            "Export-Package" to "cloud.jengu.dbo.auth;version=0.1.0",
        ))
    }
}
