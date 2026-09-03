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
    // the wire's JSON only — dbo-core carries no other dependency, and the
    // HTTP client and server are the JDK's
    implementation(project(":core:dbo-core"))
    // The participation client, for the half of this that acts. Reading is
    // this module's own HTTP against doors that answer JSON; ACTING goes
    // through the lane, and the lane already has a client. A second
    // implementation of those verbs, spelled slightly differently, is how a
    // surface comes to answer one thing to a runner and another to an
    // operator — so the reader holds the same HttpLane a runner holds.
    api(project(":core:dbo-runner"))
    // a plain jar, not a bundle: it needs the binding on its own classpath,
    // for the startup and shutdown lines and nothing per request
    implementation("org.slf4j:slf4j-api:2.0.18")
    runtimeOnly(project(":core:dbo-logging"))
}
