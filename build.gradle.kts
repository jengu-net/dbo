// The published identity of the project. `dbo.repo.url` is a property so a
// repository move is one flag, not a sweep through every POM.
val projectUrl = (findProperty("dbo.repo.url") as String?) ?: "https://github.com/jengu-net/dbo"

// One-line summaries per module, used as the POM description. Maven Central
// rejects a bundle whose POM has no description, and "dbo-postgres" is not a
// description.
val moduleBlurbs = mapOf(
    "dbo-core" to "Zero-dependency object API: stored objects, identifiers, envelopes, declared handling.",
    "dbo-postgres" to "The Postgres engine: single-transaction writes, envelope indexing, history and the transactional outbox.",
    "dbo-fhir-common" to "Shared FHIR personality machinery, independent of FHIR version.",
    "dbo-fhir-stack" to "The HL7/HAPI validation engine as one shared bundle, exported to every personality.",
    "dbo-fhir-r4" to "FHIR R4 personality: R4 meaning over the shared HAPI stack.",
    "dbo-fhir-r5" to "FHIR R5 personality: R5 meaning over the shared HAPI stack.",
    "dbo-rest" to "The FHIR HTTP surface: JDK HttpServer on virtual threads, no framework.",
    "dbo-auth" to "Per-tenant OIDC authority: JWS, JWKS, SMART scopes, role grants from the organisational model.",
    "dbo-pdi" to "Personal-data isolation: identifying elements encrypted in the payload, erasure by key destruction.",
    "dbo-policy" to "Tenant policies: append-only audit, write discipline and declarative retention.",
    "dbo-subscriptions" to "Durable FHIR subscription delivery over the change feed.",
    "dbo-sync" to "Declared content dependencies streamed between tenant stores.",
    "dbo-terminology" to "Concept-per-row terminology with \$expand, \$lookup and \$validate-code.",
    "dbo-maintenance" to "Sealed, attested archives: backup, restore, portable export and import.",
    "dbo-scim" to "Per-tenant SCIM 2.0 staff provisioning over the person vault (RFC 7643/7644).",
    "dbo-tenant" to "Tenant runtime wiring: spec files to live per-tenant service sets.",
    "dbo-tenant-k8s" to "In-cluster provisioning seam backed by operator-written Kubernetes Secrets.",
    "dbo-operator" to "Kubernetes operator reconciling TenantRegistration resources.",
    "dbo-test-model" to "A non-FHIR model used to prove the engine holds no FHIR knowledge.",
    "promise" to "Requirements as code: promises declared once, cited everywhere, composed across products.",
)

// The runtime bundle set, in install order. ONE list: the serving
// distribution installs it and the Karaf development console installs it, and
// two hand-maintained copies would drift — a drift that surfaces as "resolves
// in one container, dies on first use in the other", which is this project's
// characteristic failure and the one a green build does not catch.
val dboRuntimeModules = listOf(
    ":core:dbo-core", ":core:dbo-fhir-common", ":core:dbo-postgres",
    // the HL7/HAPI engine, once, for every personality after it
    ":core:dbo-fhir-stack",
    ":core:dbo-terminology", ":core:dbo-subscriptions",
    // the shared facade every version is served through, and the definitions
    // it carries — before the faces that import it
    ":core:dbo-fhir-element",
    ":core:dbo-fhir-r4", ":core:dbo-fhir-r5", ":core:dbo-rest", ":core:dbo-auth", ":core:dbo-pdi",
    // run records, before the modules that write them
    ":core:dbo-work",
    ":core:dbo-policy", ":core:dbo-sync",
    // dbo-tenant imports it for the maintenance surface
    ":core:dbo-maintenance", ":core:dbo-tenant", ":core:dbo-tenant-k8s",
)

/**
 * The publish tasks that exist, for a caller that wants to run only some.
 *
 * Which modules are libraries is decided in one place — the notALibrary list
 * below — and a CI script that re-derived it from directory names would be a
 * second list, wrong the first time a module is added.
 */
tasks.register("listPublishTasks") {
    group = "publishing"
    description = "Prints the publish task for every module that publishes."
    val paths = provider {
        subprojects.filter { it.plugins.hasPlugin("maven-publish") }.map { it.path }.sorted()
    }
    doLast {
        paths.get().forEach { println("$it:publishAllPublicationsToJenguRepoRepository") }
    }
}

// The JDBC driver is itself an OSGi bundle.
val dboRuntimeExternalBundles = listOf("org.postgresql:postgresql:42.7.11")

// One logging arrangement for the runtime: the API as a bundle every module
// imports, dbo-logging as a FRAGMENT of it carrying the binding, and the
// ServiceLoader mediator slf4j-api requires by manifest. The mediator is a
// framework EXTENSION — it attaches to the system bundle rather than starting,
// so it has to be present before anything requiring the extender resolves.
// Separated from the runtime set because a host container may bring its own
// logging: the Karaf console does, and installing this beside it would put two
// providers of org.slf4j in one framework.
val dboLoggingBundles = listOf("org.slf4j:slf4j-api:2.0.18")
val dboLoggingModules = listOf(":core:dbo-logging")
val dboLoggingExtension =
    "org.apache.aries.spifly:org.apache.aries.spifly.dynamic.framework.extension:1.3.7"

// Development mode: set `dbo.dev=true` in ~/.gradle/gradle.properties. It is a
// machine-local convenience for the Karaf console loop and never reaches CI,
// which passes no such property. Its only effect is to skip javadoc, because
// every library module carries a javadoc jar and generating seventeen of them
// turns a four-second republish into a minute.
val dboDevMode = (findProperty("dbo.dev") as String?) == "true"

// The console's Karaf, named once: the assembly unpacks this version and the
// command bundle compiles against its shell API.
val dboKarafVersion = (findProperty("dbo.karaf.version") as String?) ?: "4.4.11"

extra["dboKarafVersion"] = dboKarafVersion
// Every test JVM's ceiling bows to the machine it runs on. The per-module
// maxHeapSize values are each suite's own minimum (the element face holds a
// version's definitions, the harness holds a container and a distribution),
// and they are what applies when no dial is set — an IDE running one module,
// or a cleared property. The dial itself ships in gradle.properties, because
// the CI runner VM holds 6g for EVERYTHING and a check whose numbers exist
// only inside a workflow is a check nobody can run (#94).
subprojects {
    // A jar built twice from one commit is the same jar. Without this it is
    // not: Gradle stamps entry timestamps and bnd stamps Bnd-LastModified, so
    // every rebuild produces different bytes and the question "did this
    // module change?" has no answer — which is what a publish that uploads
    // 242MB on every push is really failing to ask.
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

subprojects {
    // afterEvaluate, because each module sets its own developer-machine
    // number in its build script, and an override that runs first is not one.
    // A PROJECT PROPERTY rather than an environment variable, and that is a
    // lesson with three dead CI runs behind it: a long-lived Gradle daemon
    // keeps the environment it was born with, so an env var set by a later
    // step reads as absent inside the build — the dial turned and nothing
    // moved. -P travels with every invocation, daemon or not.
    afterEvaluate {
        tasks.withType<Test>().configureEach {
            // Only the main suites: a task that fixed its own small heap on
            // purpose (the dist driver) is not re-inflated by the CI dial.
            if (name == "test") {
                (findProperty("dboTestHeap") as String?)?.let { maxHeapSize = it }
            }
            (findProperty("dboTestParallelism") as String?)?.let {
                systemProperty(
                    "junit.jupiter.execution.parallel.config.fixed.parallelism", it)
            }
            // Say what actually applies, in the plain log: five CI runs died
            // to dials that LOOKED set, and the cure is the task stating its
            // own effective numbers where a log reader sees them.
            doFirst {
                logger.lifecycle("test jvm: maxHeapSize={} parallelismOverride={}",
                        maxHeapSize,
                        systemProperties[
                            "junit.jupiter.execution.parallel.config.fixed.parallelism"]
                            ?: "none (junit-platform.properties)")
            }
        }
    }
}

extra["dboRuntimeModules"] = dboRuntimeModules
extra["dboRuntimeExternalBundles"] = dboRuntimeExternalBundles
extra["dboLoggingBundles"] = dboLoggingBundles
extra["dboLoggingModules"] = dboLoggingModules
extra["dboLoggingExtension"] = dboLoggingExtension

/**
 * The HL7 core, pinned apart from HAPI's own version, for every module and
 * every configuration.
 *
 * <p>They are two families with two numbers: `ca.uhn.fhir.*` is HAPI, and
 * `org.hl7.fhir.*` is the HL7 core it ships — 8.10.1 carries 6.9.12. Taking
 * HAPI's transitive cannot serve R6: 6.9.12's `FHIRVersion` enum stops at
 * `6.0.0-ballot3`, so loading the current R6 definitions fails on the first
 * StructureDefinition it reads with `Unknown FHIRVersion code
 * '6.0.0-ballot5'`. That coupling is permanent — **a ballot needs a core
 * release that knows its code** — so this number and the definition packages
 * move together (#58).
 *
 * <p>Here rather than in the stack bundle because the bundle is not the only
 * consumer. `dbo-fhir-stack` embeds the core and exports it; the harness, the
 * bench and every module compiling against it resolve their own graph, where
 * HAPI's transitive would win. Pinned in one configuration, the jar carries
 * one version and the classpath another — two truths about the same class,
 * which is how a test passes and a container fails.
 *
 * <p>By module name rather than a list: the family is not fixed. 6.9 split
 * `org.hl7.fhir.model` and `.support` out mid-line, and a hand-written list
 * would have left one of them behind at the old version.
 */
val hl7CoreVersion = "6.10.2"
extra["hl7CoreVersion"] = hl7CoreVersion

/**
 * HAPI's own version, in one place for the same reason the HL7 core is.
 *
 * <p>It was a literal string in four build files, which is the shape of the
 * problem one level in: nothing compared them, so a bump that missed one would
 * have embedded two HAPI versions in one runtime and surfaced as a linkage
 * error rather than as a build failure (#47).
 */
val hapiVersion = "8.10.1"
extra["hapiVersion"] = hapiVersion

subprojects {
    // The BOM is a platform, not a library: it publishes constraints and has
    // no code, and Gradle refuses to be both at once.
    val isPlatform = project.path == ":dbo-bom"
    if (!isPlatform) {
        apply(plugin = "java-library")
    }
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "ca.uhn.hapi.fhir"
                && requested.name.startsWith("org.hl7.fhir.")) {
                useVersion(hl7CoreVersion)
                because("the HL7 core carries the version codes a FHIR version is served under")
            }
        }
    }
    if (!isPlatform) {
        the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    group = "cloud.jengu.dbo"
    // Snapshots on main; a release build passes -Pdbo.version=X.Y.Z (the
    // CI derives it from the v-tag) — fixed numbering for bundles AND images.
    version = (findProperty("dbo.version") as String?) ?: "0.1.0-SNAPSHOT"

    // Publish every library module. Named exclusions rather than a guess:
    // the distribution is an image, and the harness, the conformance report
    // and the bench are tools this repository runs on itself. Publishing any
    // of them would put a test rig on Maven Central under a name that
    // promises a library.
    val notALibrary = setOf(
        ":core:harness", ":core:dbo-server", ":core:conformance", ":bench:runner",
        // the development console: a stock Karaf pointed at the bundle set,
        // not an artifact anyone consumes
        ":karaf", ":karaf:commands", ":karaf:slf4j-compat")
    if (project.path !in notALibrary) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        // Central requires a sources jar and a javadoc jar beside every
        // artifact. They are cheap here and useful to a consumer reading an
        // API that is deliberately small. A platform has no sources to ship.
        if (!isPlatform) {
            the<JavaPluginExtension>().withSourcesJar()
            the<JavaPluginExtension>().withJavadocJar()
        }

        if (dboDevMode) {
            tasks.withType<Javadoc>().configureEach { enabled = false }
        }

        configure<PublishingExtension> {
            repositories {
                // The project's own repository — PUBLIC, which is the whole
                // difference from the arrangement this replaced. Artifacts
                // here are ~320MB a release, comfortably past what Maven
                // Central's size thresholds admit (see RELEASING.md), and the
                // fat OSGi bundles are what embedded-Felix consumers actually
                // resolve. Central becomes viable once the packaging fronts
                // on the status page land; the coordinates and the signing
                // are already the same, so that is a destination change.
                maven {
                    name = "JenguRepo"
                    val snapshot = version.toString().endsWith("SNAPSHOT")
                    url = uri(
                        if (snapshot) "https://repo.jengu.cloud/repository/maven-snapshots/"
                        else "https://repo.jengu.cloud/repository/maven-releases/"
                    )
                    credentials {
                        username = System.getenv("NEXUS_USERNAME") ?: findProperty("jengu.repo.user") as String? ?: ""
                        password = System.getenv("NEXUS_PASSWORD") ?: findProperty("jengu.repo.key") as String? ?: ""
                    }
                }
                // Maven Central is not published to directly: the Central
                // Portal takes ONE bundle zip for the whole release. Every
                // module stages into a shared local repository laid out the
                // Maven way, and :centralBundle zips it. Kept warm for when
                // the artifacts are small enough to go there.
                maven {
                    name = "CentralStaging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
                }
            }
            publications {
                register<MavenPublication>("maven") {
                    if (isPlatform) {
                        // The root configures subprojects before their own
                        // scripts run, so the platform component does not
                        // exist yet — the library one does, because this block
                        // applies that plugin itself.
                        afterEvaluate { from(components["javaPlatform"]) }
                    } else {
                        from(components["java"])
                    }
                    groupId = "cloud.jengu.dbo"
                    artifactId = project.name

                    pom {
                        name.set(project.name)
                        description.set(
                            moduleBlurbs[project.name]
                                ?: "A module of DBO, a multi-tenant FHIR object store."
                        )
                        url.set(projectUrl)
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("$projectUrl/blob/main/LICENSE")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("jengu")
                                name.set("Jengu Net")
                                url.set(projectUrl)
                            }
                        }
                        scm {
                            url.set(projectUrl)
                            connection.set("scm:git:${projectUrl}.git")
                            developerConnection.set("scm:git:${projectUrl}.git")
                        }
                    }

                    // Fat bundles carry their private stacks INSIDE the jar
                    // (lib/ nested jars — HAPI, fabric8, DBOS). The POM must
                    // not redeclare them as transitive dependencies, or a
                    // Gradle consumer gets every class twice.
                    pom.withXml {
                        val embedded = configurations.findByName("embedded")
                            ?.dependencies?.map { it.name }?.toSet() ?: emptySet()
                        if (embedded.isNotEmpty()) {
                            val root = asNode()
                            @Suppress("UNCHECKED_CAST")
                            val depsNodes = (root.get("dependencies") as groovy.util.NodeList)
                            for (depsNode in depsNodes.filterIsInstance<groovy.util.Node>()) {
                                val toRemove = depsNode.children()
                                    .filterIsInstance<groovy.util.Node>()
                                    .filter { dep ->
                                        val artifact = (dep.get("artifactId") as groovy.util.NodeList)
                                            .filterIsInstance<groovy.util.Node>()
                                            .firstOrNull()?.text()
                                        artifact != null && embedded.any { artifact.startsWith(it) || it.startsWith(artifact) }
                                    }
                                toRemove.forEach { depsNode.remove(it) }
                            }
                        }
                    }
                }
            }
        }

        // Signing is REQUIRED by Central and absent everywhere else. Gated on
        // the key being present so an ordinary build, and every contributor's
        // build, needs no key at all.
        val signingKey = System.getenv("SIGNING_KEY")
        val signingPassword = System.getenv("SIGNING_PASSWORD")
        if (!signingKey.isNullOrBlank()) {
            configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(the<PublishingExtension>().publications["maven"])
            }
        }
    }
}

// The development loop's one command: publish the runtime bundle set to the
// local Maven repository, where the Karaf console's bundle:watch is looking.
// Karaf only watches bundles installed from an mvn: location and re-reads them
// from the local repository, so this publish IS the pipe between an edit and a
// running container. Nothing else about the loop needs a human.
tasks.register("dev") {
    group = "development"
    description = "Publishes the runtime bundle set to ~/.m2 for the Karaf console."
    dependsOn((dboRuntimeModules + dboLoggingModules).map { "$it:publishToMavenLocal" })
    doFirst {
        if (!dboDevMode) {
            logger.lifecycle(
                "dbo: running without dbo.dev=true — javadoc will be generated for every " +
                    "module. Put `dbo.dev=true` in ~/.gradle/gradle.properties to skip it."
            )
        }
    }
}

// The Central Portal accepts one zip per deployment, laid out as a Maven
// repository. Staging is shared across modules, so this zips the lot.
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zips the staged artifacts into a Maven Central Portal bundle."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("publishMavenPublicationToCentralStagingRepository") })
    from(layout.buildDirectory.dir("staging-deploy"))
    destinationDirectory.set(layout.buildDirectory.dir("central"))
    archiveFileName.set("dbo-central-bundle.zip")
    // Central rejects a bundle carrying maven-metadata files.
    exclude("**/maven-metadata*")
}
