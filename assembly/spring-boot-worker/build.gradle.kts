import java.util.jar.JarFile

// The performing half, as one dependency a Spring Boot application adds.
//
// A bean that implements StepService becomes a step this application
// performs. The lanes it performs over come from configuration. There is no
// store here and no way to get one: what this carries is the work vocabulary
// and the lane, which is the whole of what a party outside the deployment
// compiles against — the same line `sample/participant` draws, and the same
// one a driver bundle rests on in the container.
//
// The plan this module is being built to is README.md beside this file.

val springBootVersion = rootProject.extra["dboSpringBootVersion"] as String

// The bundle set, declared here rather than in the root build: this is its
// only consumer. It moves up the moment a second artifact installs the same
// set, on the reason the runtime list moved up — two hand-maintained copies
// drift, and the drift surfaces as a package that resolves in one container
// and dies on first use in the other.
//
// It is the set a container needs to perform work and nothing else. No store
// bundle, no face, no tenant: their absence is part of the claim, and it is
// the claim the container already proves from the other side, where a driver
// bundle contributes a step with exactly these installed.
val workerModules = listOf(
    ":core:dbo-core", ":core:dbo-work",
    // the seam every emitting path runs through, and the exporter beside it:
    // installed even where nothing exports, because a code path first run in
    // production is the last place to first run it
    ":core:dbo-telemetry", ":core:dbo-telemetry-otlp",
    ":core:dbo-runner",
    // Closure, not preference: dbo-runner's exported API refers to the
    // telemetry seam, so sharing the first without the second puts one of
    // each class beside the application and another inside a bundle. The
    // boot-time check names it; this is the answer to it.
    ":core:dbo-telemetry",
    // The carrier a worker inside the deployment uses: a lane over the store's
    // own substrate rather than over its own port. Installed unconditionally
    // and inert unless configured — the activator reads the tenants it is a
    // host for and returns where there are none, so an application that only
    // holds HTTP lanes pays a bundle and nothing else.
    ":core:dbo-stream",
)

// Not on anybody's COMPILE path — an application compiles against the API
// below and against nothing else — but they do reach its RUNTIME path, and
// they have to: the host finds each jar by reading manifests off the
// classpath and installs it by stream, which is the only way that works from
// inside a Spring Boot fat jar, where a nested BOOT-INF/lib entry has no file
// path to hand to installBundle(String).
// Whose packages the application and the container SHARE.
//
// These are the ones an application compiles against, and the host exports
// them from the system bundle so every installed bundle wires its import to
// the application's classes rather than to its own copy. One class space is
// what lets a Spring bean implement an extension point and be believed.
//
// It is a subset, and the subset is the point. A fat bundle carries its stack
// in nested lib/ jars, which no application classloader can see — exporting
// such a bundle's packages from the system bundle would resolve and then die
// on first use, which is this repository's characteristic failure.
//
// The list is the same one the api() dependencies above are built from, so a
// package reaching the application's compile path and a package the container
// wires to it cannot drift apart.
val sharedWithTheApplication = listOf(
    ":core:dbo-core",
    ":core:dbo-work",
    ":core:dbo-runner",
    // Closure, not preference: dbo-runner's exported API refers to the
    // telemetry seam, so sharing the first without the second puts one of
    // each class beside the application and another inside a bundle. The
    // boot-time check names it; this is the answer to it.
    ":core:dbo-telemetry",
    // The carrier a worker inside the deployment uses: a lane over the store's
    // own substrate rather than over its own port. Installed unconditionally
    // and inert unless configured — the activator reads the tenants it is a
    // host for and returns where there are none, so an application that only
    // holds HTTP lanes pays a bundle and nothing else.
    ":core:dbo-stream",
)

// The ServiceLoader mediator, first and never started.
//
// NOT about logging, and that distinction cost a container start: the binding
// is the application's and stays out, but `dbo-telemetry-otlp` finds the
// exporter through ServiceLoader too and declares the same
// `osgi.serviceloader.registrar` requirement. Without the mediator it does
// not resolve, so it does not start, so the container does not come up.
//
// The FRAMEWORK EXTENSION variant, not the plain dynamic bundle: that one
// carries no ASM and imports it from the container, and an embedded Felix has
// none — so it would sit unresolved, nothing would provide the extender, and
// the failure is reported by the thing that failed. An extension attaches to
// the system bundle rather than starting, which is why it is installed first
// and why the host never calls start() on it.
val serviceLoaderMediator = rootProject.extra["dboLoggingExtension"] as String

val notShared = listOf<String>()

val bundles: Configuration = configurations.create("bundles")

configurations.named("runtimeOnly") { extendsFrom(bundles) }

dependencies {
    // The host: the framework, the computed package list, the service lookup
    // and the events. Shared rather than copied — see its README.
    api(project(":core:dbo-embedded"))

    // On the APPLICATION's classloader, and exported from the system bundle
    // at boot so the installed bundles wire to these classes rather than to
    // their own copies. One class space is what lets a Spring bean implement
    // StepService and have the runner believe it.
    sharedWithTheApplication.forEach { api(project(it)) }

    api("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")
    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    bundles(serviceLoaderMediator) { isTransitive = false }
    workerModules.forEach { bundles(project(it)) { isTransitive = false } }

    // The container this assembly boots, on the test runtime path, so the
    // test reaches the bundles the way an application does.
    // Newer than the rest of this repository pins: Spring Test 7 calls an
    // ExtensionContext.Store method 5.11 does not have.
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
    testImplementation("org.springframework.boot:spring-boot-test:$springBootVersion")
    testImplementation("org.springframework:spring-test:7.0.9")
    // ApplicationContextRunner's own assertions are AssertJ-typed, so the
    // context it hands back will not even compile without it on the path.
    testImplementation("org.assertj:assertj-core:3.27.3")
    // A binding, so the test sees what an application would see: every line
    // the container logs, made by the application's own LoggerFactory.
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// No logging binding is installed, for the reason the server assembly beside
// this one states: the application already has one, and org.slf4j is exported
// from the system bundle so every bundle logs into it.

val bundleIndex = tasks.register("bundleIndex") {
    description = "Writes the ordered bundle set the embedded container installs."
    val declared = listOf(serviceLoaderMediator.split(":")[1]) +
        workerModules.map { it.substringAfterLast(':') }
    val jars = bundles
    val index = layout.buildDirectory.file("generated/dbo/META-INF/dbo/bundles.index")
    inputs.files(jars)
    inputs.property("declared", declared)
    outputs.file(index)
    doLast {
        val byModule = jars.resolvedConfiguration.resolvedArtifacts
            .associateBy({ it.moduleVersion.id.name }, { it.file })
        val lines = declared.map { module ->
            val jar = byModule[module] ?: throw GradleException(
                "$module is declared in the bundle set and did not resolve to a jar")
            JarFile(jar).use { open ->
                val symbolic = open.manifest?.mainAttributes
                    ?.getValue("Bundle-SymbolicName")
                    ?: throw GradleException("$jar carries no Bundle-SymbolicName, so it is " +
                        "not a bundle and the container cannot install it")
                symbolic.substringBefore(';').trim()
            }
        }
        index.get().asFile.parentFile.mkdirs()
        index.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

// The symbolic names whose exports the host hands to the system bundle. Names
// rather than packages: the host reads the versions off the same manifests it
// installs from, so a package added to an exported bundle is shared without
// anything here being edited.
val sharedIndex = tasks.register("sharedIndex") {
    description = "Writes which PACKAGES the application and the container share."
    val declared = sharedWithTheApplication.map { it.substringAfterLast(':') }
    val withheld = notShared
    val jars = bundles
    val index = layout.buildDirectory.file("generated/dbo/META-INF/dbo/shared.index")
    inputs.files(jars)
    inputs.property("declared", declared)
    inputs.property("withheld", withheld)
    outputs.file(index)
    doLast {
        val byModule = jars.resolvedConfiguration.resolvedArtifacts
            .associateBy({ it.moduleVersion.id.name }, { it.file })
        val packages = mutableListOf<String>()
        declared.forEach { module ->
            val jar = byModule[module] ?: throw GradleException(
                "$module is shared with the application and is not in the bundle set, so the "
                    + "container would offer packages nothing provides")
            val exported = JarFile(jar).use { open ->
                open.manifest?.mainAttributes?.getValue("Export-Package") ?: ""
            }
            // Clause by clause, quotes respected: a uses:= directive holds
            // commas of its own and splitting on the comma alone breaks it.
            var clause = StringBuilder()
            var quoted = false
            val clauses = mutableListOf<String>()
            exported.forEach { c ->
                if (c == '"') quoted = !quoted
                if (c == ',' && !quoted) {
                    clauses.add(clause.toString()); clause = StringBuilder()
                } else {
                    clause.append(c)
                }
            }
            if (clause.isNotEmpty()) clauses.add(clause.toString())
            clauses.map { it.substringBefore(';').trim() }
                .filter { it.isNotEmpty() && it !in withheld && it !in packages }
                .forEach { packages.add(it) }
        }
        withheld.filterNot { w -> packages.none { it == w } }.forEach {
            throw GradleException("$it is withheld and still shared")
        }
        index.get().asFile.parentFile.mkdirs()
        index.get().asFile.writeText(packages.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.named("main") {
    output.dir(mapOf("builtBy" to listOf(bundleIndex, sharedIndex)),
        layout.buildDirectory.dir("generated/dbo"))
}

tasks.test { useJUnitPlatform() }

publishing.publications.named<MavenPublication>("maven") {
    artifactId = "dbo-spring-boot-worker"
}
