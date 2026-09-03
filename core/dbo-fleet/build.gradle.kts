// The fleet reader: one process outside every container that reads across a
// deployment's nodes and tenants over the doors each already serves, and
// labels every answer with the node it came from. A plain jar, NOT an OSGi
// bundle — the provisioning operator's shape — because it reaches nothing but
// HTTP, holds no store, and belongs in its own pod with its own credentials.
//
// It queries and never copies: there is no store here on purpose, and a
// dependency that brought one would be the second store the control plane
// was decided never to become.

plugins {
    application
}

application {
    mainClass = "cloud.jengu.dbo.fleet.Main"
}

dependencies {
    // the wire's JSON only — dbo-core carries no other dependency, and this
    // module adds none: the HTTP client is the JDK's
    implementation(project(":core:dbo-core"))
}
