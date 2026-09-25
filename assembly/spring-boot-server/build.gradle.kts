import java.util.jar.JarFile

// The serving half, as one dependency a Spring Boot application adds.
//
// What it is: the same bundle set the serving distribution installs, hosted
// inside the application's own JVM by a Felix the application never sees.
// A bean that implements an extension point is registered on the container's
// whiteboard; a service the container registers is injectable back. Nothing
// in the published API names a Bundle, a BundleContext or a ServiceReference.
//
// The bundle set is `dboRuntimeModules` in the root build — the SAME list the
// serving distribution and the development console install from. Copying it
// here would produce the drift the single list exists to prevent: resolves in
// one container, dies on first use in the other.
//
// The plan this module is being built to is README.md beside this file.

val springBootVersion = rootProject.extra["dboSpringBootVersion"] as String

// The bundles installed into the embedded framework.
//
// Not on anybody's COMPILE path — an application compiles against the API
// below and against nothing else — but they do reach its RUNTIME path, and
// they have to: the host finds each jar by reading manifests off the
// classpath and installs it by stream, which is the only way that works
// from inside a Spring Boot fat jar, where a nested BOOT-INF/lib entry has
// no file path to hand to installBundle(String).
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
    ":core:dbo-asking",
    ":core:dbo-tenant",
    ":core:dbo-fhir-common",
    // The authority a tenant publishes. Shared because an application takes
    // it off the whiteboard to verify its tenants own tokens, and a second
    // copy of the class would make that handle uncastable. It embeds no
    // stack of its own, so its packages are loadable from the application
    // classloader — which is the test a bundle has to pass to be on this
    // list at all.
    ":core:dbo-auth",
    // And the guard seam the authority implements. Sharing dbo-auth without
    // this was not half-right, it was broken: AuthorityAuthenticator
    // implements RequestAuthenticator, and with one of the two classes coming
    // from the application and the other from a bundle the runtime met
    // IncompatibleClassChangeError on the first guarded read. The set has to
    // be closed over what its own API refers to, which is now checked at
    // boot rather than left to whoever edits this list.
    ":core:dbo-rest",
    // Closure, not preference. dbo-tenant's own exported API refers to all
    // of these, and dbo-runner's refers to the telemetry seam — so sharing
    // the first without the rest puts one of each class beside the
    // application and another inside a bundle. The boot-time check names
    // them; this list is the answer to it. Each carries no nested stack, so
    // each is loadable from the application classloader, which is the test a
    // bundle has to pass to be here.
    ":core:dbo-definitions",
    ":core:dbo-maintenance",
    ":core:dbo-pdi",
    ":core:dbo-policy",
    ":core:dbo-sync",
    ":core:dbo-telemetry",
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

// Packages an otherwise shared bundle keeps to ITSELF.
//
// `cloud.jengu.dbo.tenant` is the tenant runtime's implementation — the
// manager, the activator, every handler — and the bundle reaches HikariCP
// privately over its own Bundle-ClassPath. Supplied from outside the
// framework it loads from a classloader where that pool is not, and a
// bring-up that cannot make one fails into the trouble ledger rather than
// loudly: measured at twelve minutes with no tenant serving, against forty
// seconds with all of them.
//
// What a host implements against is `cloud.jengu.dbo.tenant.api`, which
// carries no implementation and is shared. That split is why this list has
// one entry rather than being a reason not to share the bundle at all.
val notShared = listOf("cloud.jengu.dbo.tenant")

val bundles: Configuration = configurations.create("bundles")

configurations.named("runtimeOnly") { extendsFrom(bundles) }

// Felix itself, which IS on the consumer's runtime path — the host constructs
// the framework, so the launcher's classes have to be loadable by the host.
dependencies {
    // The host: the framework, the computed package list, the service lookup
    // and the events. Shared rather than copied — see its README.
    api(project(":core:dbo-embedded"))

    // The API the application compiles against, on the APPLICATION's
    // classloader. The same packages are exported from the system bundle at
    // boot, so every installed bundle wires its import to these classes
    // rather than to its own copy: one class space, which is what lets a
    // Spring bean implement TenantLifecycleListener and be believed.
    sharedWithTheApplication.forEach { api(project(it)) }

    api("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")

    // The servlet API, where the application has one. Optional on purpose:
    // an application with no web tier mounts the surfaces on a port of their
    // own, which is what the serving distribution does, and it should not
    // acquire a servlet container by depending on this jar.
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")
    compileOnly("org.springframework:spring-web:7.0.9")
    compileOnly("org.springframework.boot:spring-boot-web-server:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")
    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    @Suppress("UNCHECKED_CAST")
    val runtimeModules = rootProject.extra["dboRuntimeModules"] as List<String>
    @Suppress("UNCHECKED_CAST")
    val runtimeExternal = rootProject.extra["dboRuntimeExternalBundles"] as List<String>
    bundles(serviceLoaderMediator) { isTransitive = false }
    runtimeModules.forEach { bundles(project(it)) { isTransitive = false } }
    // The definition packages, as a fragment of the face. NOT a runtime
    // module: a serving node installs none of them and then cannot build a
    // worker context at all. This assembly hosts the container inside
    // somebody's application, and an application declaring a tenant that takes
    // its face from no root has to build one — so it carries them, and a host
    // wanting the property drops this and the line naming it in bundleIndex.
    bundles(project(":core:dbo-fhir-packages")) { isTransitive = false }
    runtimeExternal.forEach { bundles(it) { isTransitive = false } }

    // Newer than the rest of this repository pins, and it has to be: Spring
    // Test 7 calls an ExtensionContext.Store method that 5.11 does not have,
    // and the failure is a NoSuchMethodError before a single test runs.
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.1.0")
    // MockHttpServletRequest/Response: the adapter is driven with the
    // servlet objects a container would hand it, and nothing is bound.
    testImplementation("org.springframework:spring-test:7.0.9")
    testImplementation("org.springframework:spring-web:7.0.9")
    testImplementation("org.springframework.boot:spring-boot-test:$springBootVersion")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
    // A real application: a real servlet container, a real database, a real
    // tenant. The claim this assembly exists to make cannot be made against
    // any of them mocked.
    testImplementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    // The same line the harness uses. The 1.x coordinates resolve and then
    // cannot find a Docker environment on this machine, which is a failure
    // that names the daemon rather than the dependency.
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The logging arrangement is DELIBERATELY absent from the set above.
//
// A Spring Boot application brings its own binding, and two providers of
// org.slf4j in one framework is a race rather than a posture — the same call
// the development console makes, for the same reason. org.slf4j is exported
// from the system bundle instead, so every line a bundle logs is made by the
// application's own slf4j-api and lands in its own appenders. That is the
// whole of the logging bridge: no code, no forwarder, no second format.

// What the host installs, in the order the root build declares it.
//
// Order is preserved by walking the declared list rather than the resolved
// file set, which has no order worth relying on. Symbolic names rather than
// file names, because at runtime the jars are found by reading manifests off
// the classpath — a Spring Boot fat jar has no file paths to hand out.
val bundleIndex = tasks.register("bundleIndex") {
    description = "Writes the ordered bundle set the embedded container installs."
    @Suppress("UNCHECKED_CAST")
    val declared = (listOf(serviceLoaderMediator.split(":")[1]) +
        (rootProject.extra["dboRuntimeModules"] as List<String>)
            .map { it.substringAfterLast(':') } +
        (rootProject.extra["dboRuntimeExternalBundles"] as List<String>)
            .map { it.split(":")[1] })
        // The packages fragment, beside its host and before it, because a
        // fragment attaches when its host RESOLVES. It is not in the runtime
        // module list on purpose, so it is named here rather than inherited.
        .flatMap {
            if (it == "dbo-fhir-element") listOf("dbo-fhir-packages", it) else listOf(it)
        }
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

tasks.test {
    useJUnitPlatform()
    // The serving bundle set carries the HL7/HAPI engine and two faces. It is
    // installed rather than exercised here — no tenant comes up — but the
    // classloaders alone want more than a default heap.
    // The R5 validator wants 2g on its own, and this suite brings a tenant up
    // inside an application that is also running a servlet container and a
    // database client. Stated here rather than inherited, per the rule that a
    // test task loading the validator says its own number.
    maxHeapSize = "3g"
}

// The coordinate an application types. The directory is named for what it
// assembles; the artifact is named for what it is resolved as, and a Maven
// coordinate reading `spring-boot-server` would say nothing about whose.
publishing.publications.named<MavenPublication>("maven") {
    artifactId = "dbo-spring-boot-server"
}
