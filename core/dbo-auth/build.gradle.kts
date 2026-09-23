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
            // SUBSTITUTABLE: this bundle imports what it exports.
            //
            // Policy, not inventory — the packages are named, the rest is
            // still bnd's to compute. bnd writes an import for a bundle's own
            // export only when some OTHER package inside the bundle uses it,
            // and a bundle whose code is one package never does. Without the
            // import the bundle can only ever wire to itself, so a host that
            // supplies these classes from outside the framework is ignored:
            // the two copies then differ, and the framework HIDES a service
            // whose type the consuming bundle loads differently. Nothing is
            // logged and nothing throws; an extension point simply never
            // fires. In a container with no other provider this changes
            // nothing at all — the bundle wires to itself, as before.
            "Import-Package" to "cloud.jengu.dbo.auth,*",
            "Export-Package" to "cloud.jengu.dbo.auth;version=0.1.0",
        ))
    }
}
