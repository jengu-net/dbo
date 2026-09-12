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
    "dbo-definitions" to "Element-per-row definitions: a snapshot expanded once, located by jsonpath.",
    "dbo-maintenance" to "Sealed, attested archives: backup, restore, portable export and import.",
    "dbo-scim" to "Per-tenant SCIM 2.0 staff provisioning over the person vault (RFC 7643/7644).",
    "dbo-tenant" to "Tenant runtime wiring: spec files to live per-tenant service sets.",
    "dbo-tenant-k8s" to "In-cluster provisioning seam backed by operator-written Kubernetes Secrets.",
    "dbo-operator" to "Kubernetes operator reconciling TenantRegistration resources.",
    "dbo-test-model" to "A non-FHIR model used to prove the engine holds no FHIR knowledge.",
    "promise" to "Requirements as code: promises declared once, cited everywhere, composed across products.",
    "dbo-promises" to "The store's own promise catalogue: SHAPE and PDI as the pilot.",
    "dbo-runner" to "The embeddable step runner: register step services, work arrives, outcomes and vitals go back.",
    "dbo-stream" to "The lane over the store's own stream: the same verbs, carried on the durable substrate.",
    "dbo-telemetry-otlp" to "The telemetry exporter: the seam's numbers to a collector as OTLP over HTTP, no protocol library.",
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
    ":core:dbo-terminology", ":core:dbo-definitions", ":core:dbo-subscriptions",
    // the shared facade every version is served through, and the definitions
    // it carries — before the faces that import it
    ":core:dbo-fhir-element",
    // the promise framework and the store's catalogue: leaf bundles the
    // citing modules (dbo-pdi first) import from
    ":promise", ":core:dbo-promises",
    // the telemetry seam: a leaf like the two above, imported by anything
    // that reports a number and carrying no exporter of its own. It has to be
    // installed even where nothing exports, because the emitting code path
    // runs everywhere and only its destination differs.
    ":core:dbo-telemetry",
    // the exporter beside the seam: installed everywhere, idle without an
    // endpoint, because a code path first run in production is the last
    // place to first run it
    ":core:dbo-telemetry-otlp",
    ":core:dbo-fhir-r4", ":core:dbo-fhir-r5", ":core:dbo-rest", ":core:dbo-auth", ":core:dbo-pdi",
    // the provisioning door: imports auth and pdi, imported by dbo-tenant
    ":core:dbo-scim",
    // run records, before the modules that write them
    ":core:dbo-work",
    ":core:dbo-policy", ":core:dbo-sync",
    // the lane and the surface a tenant serves it on — dbo-tenant
    // imports it to mount the participation surface, so the runner bundle
    // is in the container as the HOST's half. Its own half (the activator,
    // step services) is still only installed where work is actually done
    ":core:dbo-runner",
    // the third carrier for the lane, imported by dbo-tenant for the door
    ":core:dbo-stream",
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
val dboRuntimeExternalBundles = listOf("org.postgresql:postgresql:42.7.13")

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
// only inside a workflow is a check nobody can run.
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
            // Opt-in GC logging, for measuring what the suite actually holds.
            // A property rather than a hand-edit, for the same reason the heap
            // dial is one: the number that matters is the one measured at the
            // CI dials, and a measurement somebody has to re-derive by editing
            // a build file is one nobody repeats. Off unless asked for; %p so
            // each forked worker writes its own.
            (findProperty("dboTestGcLog") as String?)?.let {
                jvmArgs("-Xlog:gc:file=$it-%p.log")
            }
            (findProperty("dboTestParallelism") as String?)?.let {
                systemProperty(
                    "junit.jupiter.execution.parallel.config.fixed.parallelism", it)
                // One means one. A fixed parallelism of one is a ForkJoin pool
                // of one worker, and a pool compensates for a worker that
                // blocks — a class whose bring-up waits on a latch — by
                // starting another, so "sequential" classes ran three at a
                // time and three versions of the toolchain met in one heap.
                if (it == "1") {
                    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
                }
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
 * move together.
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
 * error rather than as a build failure.
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
                    // The host is the workflow's to name (ARTIFACT_HOST, as
                    // DBO_REPO_HOST here), because it is the one fact about
                    // publishing that changes when the fleet moves. The
                    // default is the public name consumers resolve from.
                    val repoHost = System.getenv("DBO_REPO_HOST")?.takeIf { it.isNotBlank() } ?: "repo.jengu.cloud"
                    url = uri(
                        if (snapshot) "https://$repoHost/repository/maven-snapshots/"
                        else "https://$repoHost/repository/maven-releases/"
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

// ─── Working rules, projected ──────────────────────────────────────
//
// The rules a green build cannot enforce are stated once, in a constraints
// document, and projected from there into installable skills and into the
// trap section of CLAUDE.md. A projection that can drift silently is a copy,
// so the verify task regenerates and then refuses a dirty working tree —
// the same arrangement the requirement catalogue is under, for the same
// reason. Hung off `check` so it runs wherever the build does, rather than
// in a lane somebody has to remember.
val generateSkills by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Projects the constraints documents' skill-blocks into tools/dbo-conventions/."
    workingDir = rootDir
    commandLine("python3", "scripts/generate-skills.py")
    inputs.dir(layout.projectDirectory.dir("docs/arc42-002-constraints"))
    inputs.file(layout.projectDirectory.file("scripts/generate-skills.py"))
    outputs.dir(layout.projectDirectory.dir("tools/dbo-conventions"))
    outputs.file(layout.projectDirectory.file("CLAUDE.md"))
}

val verifySkillProjection by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails when the committed skills or CLAUDE.md disagree with their source."
    dependsOn(generateSkills)
    workingDir = rootDir
    commandLine("git", "diff", "--exit-code", "--stat", "tools/dbo-conventions", "CLAUDE.md")
}

// Hung off the harness's own check, which is the one task every lane of the
// build already reaches. A root-level `check` looks tidier and is not run by
// `./gradlew build` at all — a wiring that resolves, configures, and is never
// invoked is exactly the failure the reachability rule describes.
project(":core:harness").tasks.named("check") { dependsOn(verifySkillProjection) }

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

// ─── The site ──────────────────────────────────────────────────────
//
// The published site is a BUILD OUTPUT assembled from three sources: the
// specification tree in docs/, the hand-written pages in site/, and whatever
// the build itself generates into docs/ (the conformance reports, today).
//
// It is assembled rather than rendered in place, and that is the point. docs/
// stays plain markdown that reads correctly on GitHub — the same file, the
// same relative links, no front matter a reader has to look past. The site is
// a second thing made from it. Rendering in place is what made the previous
// arrangement surprising: a page could stop being published because of a
// setting in a file three directories away, and nothing said so.
//
//   ./gradlew site        # build/site — what gets published
//   ./gradlew siteServe   # the same thing at localhost:8000, live-reloading
//
// The toolchain is Python, which is a second toolchain in a JVM repository and
// worth a sentence. It is here because MkDocs resolves relative `.md` links
// natively: the specification cross-references itself densely and every one of
// those links has to keep working both on GitHub and on the site. Every
// alternative either needed a link-rewriting hook to maintain or wanted the
// tree converted out of markdown. The dependencies are pinned exactly in
// site/requirements.txt and live in build/, so nothing is installed globally.

val siteDir = layout.projectDirectory.dir("site")
val siteVenv = layout.buildDirectory.dir("site-venv")
val siteSrc = layout.buildDirectory.dir("site-src")
val siteOut = layout.buildDirectory.dir("site")

val siteTools by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Creates the pinned Python environment the site is built with."
    inputs.file(siteDir.file("requirements.txt"))
    outputs.dir(siteVenv)
    // --upgrade so that changing a pin actually changes the environment
    // rather than leaving whatever was installed first.
    commandLine(
        "bash", "-c",
        "python3 -m venv '${siteVenv.get().asFile}' && " +
            "'${siteVenv.get().asFile}/bin/pip' install --quiet --upgrade " +
            "-r '${siteDir.file("requirements.txt").asFile}'",
    )
}

// ─── The diagrams ──────────────────────────────────────────────────────
//
// A figure is written in Lini (`site/diagrams/*.lini`) and compiled to SVG
// (`site/assets/diagrams/*.svg`). BOTH are committed, and that is the point:
// the site build consumes the SVG and never needs the compiler, so publishing
// stays a Python-only job that a fork can run.
//
// The compiler is a Rust binary and there is no released artifact for it, so
// it is a tool a diagram AUTHOR installs rather than one the build assumes:
//
//   cargo install lini --locked
//   ./gradlew siteDiagrams        # rewrite the SVGs from the .lini sources
//   ./gradlew siteDiagramsCheck   # fail if a committed SVG has drifted
//
// The font is the trap. Lini bakes text positions using its own bundled face
// and centres every string on the baked point, so a browser that substitutes
// another face renders alignment that is correct in the source and wrong on
// the screen. Its own answer, `--embed-font`, writes three @font-face rules
// into EVERY diagram: measured at 357kB against 11kB for the same drawing.
//
// So the faces are served once from site/assets/lini-font.css and the sources
// name the family (`font-family: "Lini Sans"`). `siteDiagramFont` regenerates
// that file, and is the thing to rerun when lini is upgraded.
//
// Set `-Plini=/path/to/lini` if the binary is not on PATH.
val liniBin = (findProperty("lini") as String?) ?: "lini"
val diagramOut = siteDir.dir("assets/diagrams")

// A diagram's source lives with what it draws — in the `diagrams/` directory
// of the concept whose figure it is — and there is deliberately no second
// place to put one. The compiled SVG lands in one flat directory, because a
// page includes it by path and that path should not encode where the drawing
// happens to be filed.
fun diagramSources(): List<File> =
    layout.projectDirectory.dir("docs").asFile.walkTopDown()
        .filter { it.isFile && it.extension == "lini" && it.parentFile.name == "diagrams" }
        .toList()
        .sortedBy { it.name }

fun liniCommand(target: File) = listOf(
    "bash", "-c",
    diagramSources().joinToString(" && ") { src ->
            val svg = File(target, src.nameWithoutExtension + ".svg")
            "'$liniBin' --strict '${src.absolutePath}' -o '${svg.absolutePath}'"
        }.ifEmpty { "true" },
)

// The accessible name and description, which Lini cannot carry.
//
// `hint:` reaches a node and is ignored on the root, and there is no property
// for a description at all — so a compiled figure arrives with neither, where
// the hand-drawn ones it replaced had both. A companion `.desc` beside each
// source holds them: the first line is the title, the rest is the prose. It
// is required, not optional, because a figure nobody can describe in a
// sentence is usually a figure that has not decided what it is about.
//
// Injected into the SVG rather than into the page, so the committed artifact
// is the whole figure — which also puts the text under `siteDiagramsCheck`,
// where an edited description drifts exactly like an edited drawing.
fun describe(target: File) {
    diagramSources().forEach { src ->
        val name = src.nameWithoutExtension
        val desc = File(src.parentFile, "$name.desc")
        if (!desc.exists()) throw GradleException("$name has no $name.desc — every figure states what it shows")
        val lines = desc.readLines()
        val title = lines.first().trim()
        val prose = lines.drop(1).joinToString(" ").trim().replace(Regex("\\s+"), " ")
        if (title.isEmpty() || prose.isEmpty()) throw GradleException("$name.desc needs a title line and a description")

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val svg = File(target, "$name.svg")
        var text = svg.readText()
        val open = Regex("<svg\\b[^>]*>").find(text) ?: throw GradleException("$name.svg has no root element")
        val root = open.value.dropLast(1) +
            " role=\"img\" aria-labelledby=\"$name-title $name-desc\">"
        text = text.replaceRange(
            open.range,
            root + "\n  <title id=\"$name-title\">${esc(title)}</title>" +
                "\n  <desc id=\"$name-desc\">${esc(prose)}</desc>",
        )
        svg.writeText(text)
    }
}

val siteDiagrams by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Compiles site/diagrams/*.lini to site/assets/diagrams/*.svg."
    inputs.files(diagramSources())
    outputs.dir(diagramOut)
    doFirst { diagramOut.asFile.mkdirs() }
    commandLine(liniCommand(diagramOut.asFile))
    doLast { describe(diagramOut.asFile) }
}

// The ratchet. A generated file that is committed drifts silently the first
// time somebody edits the output instead of the source, or upgrades the
// compiler without rerunning it — so the check recompiles into a scratch
// directory and diffs, the way the reach ledger and the exported-API record
// fail when the tree stops matching what was recorded.
// One diagram compiled with the faces embedded, with everything but the
// @font-face rules thrown away. Regenerating is a deliberate act rather than
// a build step: the output is 340kB of base64 that changes only when the
// compiler does.
val siteDiagramFont by tasks.registering {
    group = "documentation"
    description = "Rewrites site/assets/lini-font.css from lini's bundled faces."
    doLast {
        // Every source, not one: a diagram that reaches for the mono family
        // embeds a face the others never mention, and sampling one file would
        // leave that diagram rendering in whatever the browser falls back to.
        val sources = diagramSources()
        if (sources.isEmpty()) throw GradleException("no .lini source to take the faces from")
        val faces = sources.flatMap { src ->
            val embedded = providers.exec {
                commandLine(liniBin, "--strict", "--embed-font", src.absolutePath)
            }.standardOutput.asText.get()
            Regex("""@font-face\s*\{.*?\}""", RegexOption.DOT_MATCHES_ALL)
                .findAll(embedded).map { it.value }.toList()
        }.distinct().sorted()
            // The embed pass renames the bundled families to "Lini Sans*",
            // but a plain SVG asks the browser for the names the COMPILER
            // knows — which are the ones the sources must use, because that
            // is what it measures with. Serving the faces under those names
            // is what makes a diagram render in the face it was measured in.
            .map { it.replace("\"Lini Sans Code\"", "\"Google Sans Code\"")
                     .replace("\"Lini Sans\"", "\"Google Sans\"") }
        if (faces.isEmpty()) throw GradleException("lini embedded no @font-face rules")
        // The header is kept and the faces are replaced wholesale. Writing
        // them back without the separating newline once let a regeneration
        // read its own output as header and accumulate the previous run's
        // faces beside the new ones.
        val css = siteDir.file("assets/lini-font.css").asFile
        val header = css.readLines().takeWhile { !it.trimStart().startsWith("@font-face") }
            .joinToString("\n").trimEnd()
        css.writeText(header + "\n" + faces.joinToString("\n") + "\n")
        logger.lifecycle("recorded ${faces.size} faces from ${sources.size} sources")
    }
}

val siteDiagramsCheck by tasks.registering {
    group = "verification"
    description = "Fails when a committed diagram SVG differs from its .lini source."
    // Asked for in the same invocation as the compile, Gradle is free to run
    // this first and fail against SVGs that were about to be rewritten.
    mustRunAfter(siteDiagrams)
    inputs.files(diagramSources())
    inputs.dir(diagramOut)
    val scratch = layout.buildDirectory.dir("site-diagrams-check")
    doLast {
        val dir = scratch.get().asFile
        dir.deleteRecursively(); dir.mkdirs()
        providers.exec { commandLine(liniCommand(dir)) }.result.get().assertNormalExitValue()
        describe(dir)
        val drifted = dir.listFiles().orEmpty().filter { fresh ->
            val committed = File(diagramOut.asFile, fresh.name)
            !committed.exists() || committed.readText() != fresh.readText()
        }
        if (drifted.isNotEmpty()) {
            throw GradleException(
                "these diagrams no longer match their source: " +
                    drifted.joinToString(", ") { it.name } +
                    " — run ./gradlew siteDiagrams and commit the result",
            )
        }
    }
}

val siteAssemble by tasks.registering(Sync::class) {
    group = "documentation"
    description = "Assembles the site's source tree from docs/ and site/."
    into(siteSrc)

    // The hand-written pages sit at the root: the landing page, the essays,
    // and the nav files that order them.
    from(siteDir.dir("pages"))
    from(siteDir.dir("assets")) { into("assets") }

    // The specification tree, mounted one level down so the hand-written
    // pages own the root and the reference owns /docs/.
    from(layout.projectDirectory.dir("docs")) {
        into("docs")
        // tasks/ is the live agenda and plans/ is what is proposed rather
        // than what is; both stay readable in the repository and neither is
        // part of the published specification. The consequence, which outlived
        // the Jekyll config that used to state it: a page that IS published
        // must not reach either tree with a relative link, because such a link
        // resolves in the repository and 404s here. The few that need to point
        // there use an absolute repository URL instead.
        // A `why-` page is an essay that lives with the concept it argues, and
        // it is collected to /why/ below rather than rendered twice here. A
        // concept's diagrams are compiler sources, not pages.
        exclude("tasks/**", "plans/**", "**/why-*.md", "**/diagrams/**")
    }
    from(layout.projectDirectory.file("docs/favicon.ico")) { into("assets") }

    // The essays are excluded from the copy above and collected in doLast, so
    // Gradle cannot see them as inputs — and an up-to-date Sync skips the
    // collector entirely. Naming them makes an edited essay rebuild the site.
    inputs.files(
        layout.projectDirectory.dir("docs").asFile.walkTopDown()
            .filter { it.isFile && it.name.startsWith("why-") && it.extension == "md" }
            .toList(),
    )

    // The essays, gathered from wherever they live.
    //
    // An essay belongs beside the concept it argues — whoever edits
    // `data-isolation` should find its drawings and its prose in one place —
    // but a reader arriving at the site wants /why/, not a path through the
    // specification. So the front matter carries `why: <rank>`, the page is
    // collected here under the name it had, and the reading order is computed
    // from the ranks rather than kept in a second list that can disagree.
    //
    // The order is not alphabetical and never was: what the thing IS comes
    // first, because every page after it says "the engine" and means
    // something particular; then work, which is what people are most
    // surprised a store does at all; then the three properties a deployment
    // is judged on — who is separated from whom, what a record is held to,
    // and how anybody gets in; then the two pages about the gap between what
    // was declared and what is true, which is where operating it actually
    // lives; last, the two mechanisms that keep copies and jurisdictions
    // honest.
    doLast {
        val fromConcepts = layout.projectDirectory.dir("docs").asFile.walkTopDown()
            .filter { it.isFile && it.name.startsWith("why-") && it.extension == "md" }
        val fromPages = siteDir.dir("pages/why").asFile.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name != "index.md" }
        val essays = (fromConcepts + fromPages).map { f ->
            val rank = Regex("^why:\\s*(\\d+)\\s*$", RegexOption.MULTILINE)
                .find(f.readText())?.groupValues?.get(1)?.toInt()
                ?: throw GradleException(f.name + " sits in a why position and states no `why:` rank")
            rank to f
        }.sortedBy { it.first }.toList()

        // An essay is written to be read WHERE IT IS STORED — beside its
        // concept, linking to `README.md` as the sibling it is — and then
        // published somewhere else. So the reference frame it was moved into
        // is repaired here rather than by asking the author to write links
        // that are wrong in the repository and right on the site.
        val docsRoot = layout.projectDirectory.dir("docs").asFile.canonicalFile
        fun reframe(essay: File, text: String): String {
            if (!essay.canonicalPath.startsWith(docsRoot.path)) return text
            return Regex("]\\((?!https?:|/|#)([^)#]+)(#[^)]*)?\\)").replace(text) { m ->
                val target = File(essay.parentFile, m.groupValues[1]).canonicalFile
                if (target.name.startsWith("why-") && target.extension == "md") {
                    "](" + target.name.removePrefix("why-") + m.groupValues[2] + ")"
                } else if (target.path.startsWith(docsRoot.path)) {
                    "](../docs/" + target.relativeTo(docsRoot).path.replace(File.separatorChar, '/') +
                        m.groupValues[2] + ")"
                } else {
                    m.value
                }
            }
        }

        val out = siteSrc.get().dir("why").asFile
        out.mkdirs()
        essays.forEach { (_, f) ->
            File(out, f.name.removePrefix("why-")).writeText(reframe(f, f.readText()))
        }

        val lines = mutableListOf(
            "# Generated by siteAssemble from each essay's `why:` rank. The order,",
            "# and the reason it is not alphabetical, are stated in build.gradle.kts.",
            "nav:",
            "  - index.md",
        )
        essays.forEach { (_, f) -> lines.add("  - " + f.name.removePrefix("why-")) }
        File(out, ".nav.yml").writeText(lines.joinToString("\n") + "\n")
        logger.lifecycle("collected " + essays.size + " essays into /why/")
    }

    // Sync deletes what is no longer produced, so a page renamed in docs/
    // does not linger in the output as a stale URL.
    doLast {
        // docs/README.md carries Jekyll front matter that pins its URL on the
        // old site. Stripped here rather than deleted there, so the two sites
        // can both work until the switch is made.
        val index = siteSrc.get().file("docs/README.md").asFile
        if (index.exists()) {
            index.writeText(index.readText().replaceFirst(Regex("(?s)\\A---\\n.*?\\n---\\n"), ""))
        }

        // A directory takes its name from its path unless something says
        // otherwise, so `the-fhir-face` renders as "The fhir face" and every
        // § number falls out of the menu — which is the one thing in this
        // tree that must keep resolving. The section is named by the document
        // that introduces it, computed here so the two cannot disagree.
        siteSrc.get().dir("docs").asFile.walkTopDown()
            .filter { it.isDirectory && File(it, "README.md").isFile && !File(it, ".nav.yml").exists() }
            .forEach { dir ->
                val h1 = File(dir, "README.md").readLines()
                    .firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                    ?: return@forEach
                File(dir, ".nav.yml").writeText(
                    "# Generated by siteAssemble from this directory's README heading.\n" +
                        "title: " + h1.replace("\"", "\\\"") + "\n",
                )
            }

        // Diagrams are inlined into markdown, and markdown decides what is a
        // raw HTML block by looking at blank lines and indentation. A drawing
        // laid out to be read by a person is therefore parsed as prose and
        // several code blocks, which is a spectacular way to fail.
        //
        // So the source stays laid out and commented, and what is inlined is
        // one line with the comments removed. Only whitespace BETWEEN tags is
        // collapsed, so a label keeps the spaces inside it.
        val diagrams = siteSrc.get().dir("assets/diagrams").asFile
        if (diagrams.isDirectory) {
            diagrams.listFiles { f -> f.extension == "svg" }?.forEach { svg ->
                svg.writeText(
                    svg.readText()
                        .replace(Regex("(?s)<!--.*?-->"), "")
                        .replace(Regex(">\\s+<"), "><")
                        .trim(),
                )
            }
        }
    }
}

val site by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Builds the site into build/site."
    dependsOn(siteTools, siteAssemble)
    inputs.dir(siteSrc)
    inputs.dir(siteDir.dir("overrides"))
    inputs.file(siteDir.file("mkdocs.yml"))
    outputs.dir(siteOut)
    workingDir = siteDir.asFile
    // --strict turns a broken cross-reference into a failed build. A
    // specification whose whole claim is that its § references resolve cannot
    // publish a link that does not.
    commandLine("${siteVenv.get().asFile}/bin/mkdocs", "build", "--strict")
}

tasks.register<Exec>("siteServe") {
    group = "documentation"
    description = "Serves the site locally with live reload."
    dependsOn(siteTools, siteAssemble)
    workingDir = siteDir.asFile
    // It serves at http://localhost:8000/dbo/ rather than at the root,
    // because site_url carries the project path GitHub Pages publishes under.
    // That is worth keeping: a relative link that only works at the root is
    // then broken locally too, rather than only after it is published.
    //
    // Only the assembled tree is watched. Editing a page under docs/ or site/
    // needs `./gradlew siteAssemble` to reach it — the alternative is mkdocs
    // watching two trees it does not own and rebuilding from a half-copied
    // one.
    commandLine("${siteVenv.get().asFile}/bin/mkdocs", "serve")
}
