// The serving distribution: the STANDARD Felix launcher
// (org.apache.felix.main) + felix.auto.deploy over bundle/ holding the
// production bundle set. No launcher code of ours — what runs in prod is
// exactly the bundles CI tests. Env→system-property mapping lives in
// bin/dbo-server; the dbo-tenant activator reads them via
// BundleContext.getProperty (framework props fall back to system props).

val felix: Configuration by configurations.creating
val bundles: Configuration by configurations.creating

dependencies {
    felix("org.apache.felix:org.apache.felix.main:7.0.5") { isTransitive = false }

    // The bundle set lives in the root build — see dboRuntimeModules there for
    // why it is one list. module bundles only: their dependencies ride
    // embedded (lib/) or are other bundles in this list, and transitive=false
    // keeps stray jars out.
    @Suppress("UNCHECKED_CAST")
    val runtimeModules = rootProject.extra["dboRuntimeModules"] as List<String>
    @Suppress("UNCHECKED_CAST")
    val runtimeExternal = rootProject.extra["dboRuntimeExternalBundles"] as List<String>
    @Suppress("UNCHECKED_CAST")
    val loggingBundles = rootProject.extra["dboLoggingBundles"] as List<String>
    @Suppress("UNCHECKED_CAST")
    val loggingModules = rootProject.extra["dboLoggingModules"] as List<String>
    val loggingExtension = rootProject.extra["dboLoggingExtension"] as String

    (runtimeModules + loggingModules).forEach { bundles(project(it)) { isTransitive = false } }
    (runtimeExternal + loggingBundles + loggingExtension).forEach {
        bundles(it) { isTransitive = false }
    }
}

val installDist = tasks.register<Sync>("installDist") {
    into(layout.buildDirectory.dir("install/dbo-server"))
    // bin/felix.jar, conf/ beside it — Felix derives its home from the
    // jar's PARENT directory (the standard dist layout); a jar at the root
    // resolves conf/ one level too high and silently boots with defaults
    from(felix) {
        rename { "felix.jar" }
        into("bin")
    }
    from(bundles) {
        into("bundle")
    }
    from("src/main/dist/conf") {
        into("conf")
    }
    from("src/main/dist/bin") {
        into("bin")
        filePermissions { unix("rwxr-xr-x") }
    }
}

tasks.assemble {
    dependsOn(installDist)
}
