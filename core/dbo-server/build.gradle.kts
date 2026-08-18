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
    // module bundles only — their dependencies ride embedded (lib/) or are
    // other bundles in this list; transitive=false keeps stray jars out
    listOf(
        ":core:dbo-core", ":core:dbo-fhir-common", ":core:dbo-postgres",
        ":core:dbo-terminology", ":core:dbo-subscriptions", ":core:dbo-fhir-r4",
        ":core:dbo-fhir-r5", ":core:dbo-rest", ":core:dbo-auth", ":core:dbo-pdi", ":core:dbo-policy", ":core:dbo-sync",
        // dbo-tenant imports it for the maintenance surface
        ":core:dbo-maintenance", ":core:dbo-tenant", ":core:dbo-tenant-k8s",
    ).forEach { bundles(project(it)) { isTransitive = false } }
    // the JDBC driver is itself an OSGi bundle
    bundles("org.postgresql:postgresql:42.7.11") { isTransitive = false }
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
