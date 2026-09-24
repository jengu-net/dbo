// The store inside an ordinary JVM: boot a framework, hand it a bundle set
// and a package list, and let the host reach what it registers.
//
// In core/ and not assembly/, because it names no framework — not one class
// here imports one. It is how an application of any shape embeds the store,
// and the Spring assemblies are glue on top of it: an application that wants
// no framework uses this directly, which is the whole of what "advanced mode"
// means.
//
// Shared code from its first line, which is why it is a module. Two hosts
// computing two package lists is the drift the whole arrangement is arranged
// against — one would export a package at a version the other does not, and
// the failure is a bundle that resolves in one application and dies on first
// use in the other.
//
// It carries no bundle set of its own. A bundle set is an assembly's own
// statement about what it installs; this reads every `META-INF/dbo/
// bundles.index` on the classpath and unions them, so an application holding
// both assemblies gets ONE framework rather than two — which for the element
// bundle is the difference between holding a parsed set of FHIR definitions
// once and holding it twice.
//
// The plan this module is being built to is README.md beside this file.

dependencies {
    api("org.apache.felix:org.apache.felix.framework:7.0.5")
    api("org.osgi:org.osgi.util.tracker:1.5.4")

    // The floor every assembly's API sits on. What each assembly adds beyond
    // this is its own, and stays its own: a worker that could reach a store
    // would have stopped being able to run outside the deployment.
    api(project(":core:dbo-core"))

    // The application's own binding, which is the whole of the logging
    // bridge: the host shares org.slf4j from the system bundle at the version
    // this jar declares, so every line a bundle logs is made here and lands
    // in the application's appenders. api, because the host reads the
    // version off this jar's manifest at boot.
    api("org.slf4j:slf4j-api:2.0.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

publishing.publications.named<MavenPublication>("maven") {
    artifactId = "dbo-embedded"
}
