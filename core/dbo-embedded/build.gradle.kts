import java.util.jar.JarFile

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
    // @Proving citations only.
    testImplementation(project(":core:dbo-promises"))
    testAnnotationProcessor(project(":promise"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// A bundle set for this module's own tests: dbo-core, the smallest bundle that
// resolves on its own, and the packages it exports as the shared list.
//
// Test scope only. The property that makes two assemblies share one framework
// is that this module installs nothing of its own in production; the set here
// is what lets it boot itself to be asked whether it works, written the way
// the assemblies write theirs so the same reader reads both.
val testBundleSet: Configuration = configurations.create("testBundleSet")
dependencies {
    testBundleSet(project(":core:dbo-core")) { isTransitive = false }
}
val testBundleIndex = tasks.register("testBundleIndex") {
    description = "Writes the bundle set this module's tests install."
    val jars = testBundleSet
    val out = layout.buildDirectory.dir("generated/test-dbo/META-INF/dbo")
    inputs.files(jars)
    outputs.dir(out)
    doLast {
        val jar = jars.singleFile
        val manifest = JarFile(jar).use { it.manifest!!.mainAttributes }
        val symbolic = manifest.getValue("Bundle-SymbolicName").substringBefore(';').trim()
        val exported = manifest.getValue("Export-Package") ?: ""
        // Clause by clause, quotes respected: a uses:= directive holds commas
        // of its own and splitting on the comma alone breaks it.
        val packages = mutableListOf<String>()
        var clause = StringBuilder()
        var quoted = false
        (exported + ",").forEach { c ->
            if (c == '"') quoted = !quoted
            if (c == ',' && !quoted) {
                val name = clause.toString().substringBefore(';').trim()
                if (name.isNotEmpty()) packages.add(name)
                clause = StringBuilder()
            } else {
                clause.append(c)
            }
        }
        val dir = out.get().asFile
        dir.mkdirs()
        dir.resolve("bundles.index").writeText(symbolic + "\n")
        dir.resolve("shared.index").writeText(packages.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.named("test") {
    output.dir(mapOf("builtBy" to listOf(testBundleIndex)),
        layout.buildDirectory.dir("generated/test-dbo"))
}

publishing.publications.named<MavenPublication>("maven") {
    artifactId = "dbo-embedded"
}
