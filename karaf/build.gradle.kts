import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

// The development console (docs/plans/karaf-console.md).
//
// A stock Apache Karaf installing the SAME bundle set as the serving
// distribution, from the local Maven repository, so `./gradlew dev` and
// Karaf's bundle:watch form a loop: an edit republishes, the container
// re-reads the bundle, and whoever is watching the log sees what happened.
//
// NOT a deployment artifact. Production runs the standard Felix launcher.
//
// Logging posture: this console keeps Karaf's own pax-logging rather than
// installing dbo-logging and its slf4j-api host. Two providers of org.slf4j in
// one framework is not a posture, it is a race — and the binding dbo ships is a
// FRAGMENT plus a framework EXTENSION, and an extension can only attach at
// framework init, which a running Karaf is past. Consequence worth knowing
// while reading this console's log: the arrangement writing these lines is
// pax-logging, not the one the distribution ships.

val karafVersion = rootProject.extra["dboKarafVersion"] as String

// Which logging arrangement the console runs.
//
//   karaf (default) — Karaf's own pax-logging. log:set and log:tail work, and
//                     levels can be changed per logger while it runs.
//   dbo             — the arrangement the distribution ships: slf4j-api as a
//                     shared bundle, dbo-logging as its fragment carrying the
//                     binding. What the product actually does, including the
//                     way it fails. Costs log:set and log:tail, and dbo-logging
//                     decides its level once at startup by design, so changing
//                     verbosity means reassembling with a different
//                     dbo.log.level. Karaf's own bundles import
//                     org.slf4j;version="[2.0,3)", which slf4j-api 2.0.18
//                     satisfies — so Karaf's own messages come out through the
//                     product's writer, at the product's single global level.
val loggingPosture = (findProperty("dbo.karaf.logging") as String?) ?: "karaf"

// The same SPI-Fly the distribution installs, as a framework EXTENSION.
//
// The dynamic bundle looks like a drop-in alternative and is not: it carries no
// ASM at all and imports org.objectweb.asm, .commons, .util and the spifly
// weaver from the container, where the extension embeds all of it. Karaf has no
// ASM, so the dynamic bundle stays unresolved, nothing provides the
// osgi.serviceloader extender, and slf4j-api and dbo-logging never resolve —
// silently, because the thing that would report it is what failed.
//
// An extension attaches to the system bundle rather than starting, so it has to
// be present before anything requiring the extender resolves. Karaf's launcher
// installs the startup set after framework init, which is early enough.
val spiFlyExtension = rootProject.extra["dboLoggingExtension"] as String

// Karaf's own plumbing — metatype, config.core, features.core — imports the
// OSGi LogService API, which in a stock Karaf comes from pax-logging-api and
// from nothing else. The distribution never needs it because its bundle set has
// none of those. So this is the console's concession to living inside Karaf,
// the same shape as exporting com.sun.net.httpserver: not part of the product's
// arrangement, just what the host requires to stand up around it.
val logServiceApi = "org.apache.felix:org.apache.felix.log:1.3.0"

// Karaf's JAAS modules log through commons-logging, which pax-logging also
// provided. The bridge routes it into slf4j and so into the product's binding,
// rather than adding a second place log lines can come out of.
val commonsLoggingBridge = "org.slf4j:jcl-over-slf4j:2.0.18"

// pax-url-aether is the mvn: URL handler, and so the thing bundle:watch reads
// through — Karaf cannot start without it and the console's whole loop rests on
// it. It is built against slf4j 1.7 and imports org.slf4j.spi;[1.7,2.0), which
// slf4j-api 2.0.18 does not satisfy. This is exactly the gap pax-logging exists
// to paper over: it exports org.slf4j.spi at BOTH generations at once.
//
// So the 1.7 API rides along beside the 2.x one. Karaf's own bundles import
// [2.0,3) and wire to the product's slf4j; pax-url wires to 1.7 and finds no
// 1.7-era binding, which makes its logging silent rather than wrong. Two API
// bundles, one binding, and nothing of the product's arrangement changed.
// pax-url-aether is the mvn: URL handler bundle:watch reads through, so Karaf
// cannot stand up without it and the console's loop rests on it. It is built
// against slf4j 1.7 and imports org.slf4j.spi;[1.7,2.0), which slf4j-api 2.0.18
// exports at 2.0.18 — the gap pax-logging papers over by exporting both
// generations at once.
//
// Supplying the real 1.7 API beside the 2.x one does NOT work: pax-url then
// took org.slf4j from 2.0.18 and org.slf4j.impl from the 1.7 binding and died
// with a loader constraint violation on ILoggerFactory, two class spaces inside
// one bundle's wiring. The fragment re-exports the host's OWN classes at 1.7
// versions instead — one class space, both ranges satisfied.
val slf4jCompatFragment = ":karaf:slf4j-compat"
val karafDist: Configuration by configurations.creating
val dboLoggingBundles: Configuration by configurations.creating
// The compat fragment, kept in its own configuration so its coordinates are
// found beside the resolved ones.
val dboLegacySlf4j: Configuration by configurations.creating

@Suppress("UNCHECKED_CAST")
val loggingGavs = (rootProject.extra["dboLoggingBundles"] as List<String>) + spiFlyExtension + logServiceApi + commonsLoggingBridge

dependencies {
    karafDist("org.apache.karaf:apache-karaf:$karafVersion@tar.gz")
    loggingGavs.forEach { dboLoggingBundles(it) { isTransitive = false } }
    dboLegacySlf4j(project(slf4jCompatFragment)) { isTransitive = false }
    @Suppress("UNCHECKED_CAST")
    (rootProject.extra["dboLoggingModules"] as List<String>).forEach {
        dboLoggingBundles(project(it)) { isTransitive = false }
    }
}

@Suppress("UNCHECKED_CAST")
val runtimeModules = rootProject.extra["dboRuntimeModules"] as List<String>
@Suppress("UNCHECKED_CAST")
val runtimeExternal = rootProject.extra["dboRuntimeExternalBundles"] as List<String>

fun moduleCoordinate(path: String) =
    "mvn:cloud.jengu.dbo/${path.substringAfterLast(':')}/${project.version}"

fun externalCoordinate(gav: String): String {
    val (group, artifact, version) = gav.split(":")
    return "mvn:$group/$artifact/$version"
}

// Karaf's launcher finds a JVM through java_home, which reports whatever is
// registered system-wide — Java 15 on this machine, against bundles compiled
// for 21. The failure is an UnsupportedClassVersionError deep in an install,
// so the console pins the same toolchain the build compiles with.
val javaToolchains = extensions.getByType<JavaToolchainService>()
val consoleLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val consoleDir = layout.buildDirectory.dir("dbo-console")

// Assembling is destructive: karafHome is a Sync, so it deletes data/ and
// etc/host.key — the state a running console is living in. Doing that under a
// live instance leaves it unable to serve a session, and the symptom (nothing
// works any more) points nowhere near the cause. Karaf writes its pid while it
// runs; refuse rather than explain this in a README.
val refuseWhileRunning = tasks.register("refuseWhileRunning") {
    doLast {
        val home = consoleDir.get().dir("karaf").asFile
        val portFile = home.resolve("data/port")
        if (!portFile.isFile) {
            return@doLast
        }
        // The file alone is not proof: it survives a crash, and a stale one
        // would block assembly for good. The socket is the liveness test, so a
        // dead instance's leftovers correct themselves.
        val port = portFile.readText().trim().toIntOrNull() ?: return@doLast
        val live = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                true
            }
        } catch (e: Exception) {
            false
        }
        if (live) {
            throw GradleException(
                "A console is running in ${home.path} (shell port $port).\n" +
                    "Assembling deletes data/ underneath it, which leaves it unable to\n" +
                    "serve a session. Stop it first:\n" +
                    "  ${home.path}/bin/stop"
            )
        }
    }
}

// Stock Karaf, unpacked. The tarball's single top-level directory is stripped
// so the console lands at a predictable path.
val karafHome = tasks.register<Sync>("karafHome") {
    description = "Unpacks a stock Karaf for the development console."
    dependsOn(refuseWhileRunning)
    into(consoleDir.map { it.dir("karaf") })
    from(tarTree(resources.gzip(karafDist.singleFile))) {
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        includeEmptyDirs = false
    }
    doLast {
        val home = consoleDir.get().dir("karaf").asFile
        home.resolve("bin").listFiles()?.forEach { it.setExecutable(true) }
    }
}

// Everything that makes the stock distribution point at this project: where the
// bundles come from, which database they serve, and the install script itself.
val console = tasks.register("console") {
    group = "development"
    description = "Assembles the Karaf development console into build/dbo-console."
    dependsOn(karafHome, ":karaf:commands:jar", ":karaf:slf4j-compat:jar")

    val local = file("dev/local.properties")
    val example = file("dev/local.properties.example")
    inputs.file(if (local.exists()) local else example)
    outputs.dir(consoleDir)

    doLast {
        val home = consoleDir.get().dir("karaf").asFile
        val settings = Properties().apply {
            load((if (local.exists()) local else example).inputStream())
        }
        if (!local.exists()) {
            logger.lifecycle(
                "dbo: karaf/dev/local.properties not found — using the example values. " +
                    "Copy it and point dbo.tenant.admin.* at your own Postgres."
            )
        }

        // bundle:watch only follows bundles whose location is an mvn: URL, and
        // it looks them up in THIS directory. Karaf's default local repository
        // is its own system/ folder, where nothing this project builds ever
        // lands; without the override the watch is silent rather than broken,
        // which is the worse failure.
        home.resolve("etc/org.ops4j.pax.url.mvn.cfg").appendText(
            "\n# dbo development console: watch the repository ./gradlew dev publishes to\n" +
                "org.ops4j.pax.url.mvn.localRepository=\${user.home}/.m2/repository\n"
        )

        // The container reads these through BundleContext.getProperty, which
        // falls back to system properties — the same mapping bin/dbo-server
        // does from the environment.
        // Karaf runs from its own directory, so a relative spec path would
        // resolve somewhere nobody meant. Relative entries are taken against
        // the repository root, which is how they read in local.properties.
        val tenantDir = rootProject.file(settings.getProperty("dbo.tenant.dir"))
        tenantDir.mkdirs()
        settings.setProperty("dbo.tenant.dir", tenantDir.absolutePath)

        val props = listOf(
            "dbo.tenant.dir", "dbo.tenant.http.host", "dbo.tenant.http.port",
            "dbo.tenant.admin.url", "dbo.tenant.admin.user", "dbo.tenant.admin.password",
            "dbo.tenant.auth.kek", "dbo.tenant.auth.issuer.base",
            "dbo.log.level", "dbo.log.format",
            "dbo.log.level.org.apache.karaf.bundle",
        ).mapNotNull { key -> settings.getProperty(key)?.let { value -> "$key=$value" } }
        home.resolve("etc/system.properties").appendText(
            "\n# dbo development console\n" + props.joinToString("\n") + "\n"
        )

        // One command per pass, mirroring felix.auto.deploy's install,start:
        // -s installs every URL before starting any, so a bundle is never
        // started against a half-present set. Installing a location that is
        // already installed returns the existing bundle, which is what makes
        // this script safe to re-run after a restart.
        // The declared set, in install order, for dbo-console:up to bring up. A
        // property rather than a script, because before the first install the
        // container has no way to know what it is meant to hold — and once it
        // does hold it, every other question is answered by asking the
        // container instead.
        val coordinates = runtimeModules.map(::moduleCoordinate) +
            runtimeExternal.map(::externalCoordinate)
        home.resolve("etc/system.properties").appendText(
            "dbo.console.bundles=" + coordinates.joinToString(",") + "\n"
        )

        // Felix computes the system bundle's exports from the running JDK's
        // modules, so the serving distribution gets com.sun.net.httpserver
        // (jdk.httpserver) for free. Karaf names its extra packages explicitly
        // and does not name that one — and the FHIR HTTP surface is a JDK
        // HttpServer on virtual threads, so without this the whole serving half
        // of the container fails to resolve. This is the framework-floor
        // difference between the two containers, in one line.
        val configProperties = home.resolve("etc/config.properties")
        val extraMarker = "org.osgi.framework.system.packages.extra = \\\n"
        configProperties.writeText(
            configProperties.readText().replace(
                extraMarker,
                extraMarker + "    com.sun.net.httpserver, \\\n" +
                    "    com.sun.net.httpserver.spi, \\\n"
            )
        )

        // A stock Karaf ships no user at all, so bin/client and the ssh port
        // have nothing to authenticate and the console can only be driven from
        // the terminal that launched it. This one is a fixed development
        // credential on a loopback-bound port; it guards a container holding
        // one throwaway database and it must never travel anywhere else.
        home.resolve("etc/users.properties").appendText(
            "\nkaraf = karaf,_g_:admingroup\n" +
                "_g_\\:admingroup = group,admin,manager,viewer,systembundles,ssh\n"
        )

        if (loggingPosture == "dbo") {
            installFeaturelessConsole(home)
        } else if (loggingPosture != "karaf") {
            throw GradleException(
                "dbo.karaf.logging=$loggingPosture — expected 'karaf' or 'dbo'."
            )
        }

        // The console's own commands go through deploy/, not the local Maven
        // repository: they are development tooling and nothing should resolve
        // them by coordinate. Karaf re-deploys a changed jar here on its own,
        // which is the same loop the watched bundles get.
        val commandsJar = project(":karaf:commands").tasks.named("jar").get().outputs.files.singleFile
        commandsJar.copyTo(home.resolve("deploy/${commandsJar.name}"), overwrite = true)

        // bin/setenv is the launcher's own hook, sourced before the JVM is
        // chosen. JAVA_MAX_MEM because the R5 validator loads the FHIR core
        // package eagerly and on a default heap dies as HAPI-2330 with a null
        // message, three frames above an OutOfMemoryError nobody sees.
        val jdkHome = consoleLauncher.get().metadata.installationPath.asFile.absolutePath
        val setenv = home.resolve("bin/setenv")
        setenv.writeText(
            "#!/bin/sh\n" +
                "# Generated by :karaf:console — do not edit, it is overwritten.\n" +
                "export JAVA_HOME=\"$jdkHome\"\n" +
                "export JAVA_MAX_MEM=2g\n"
        )
        setenv.setExecutable(true)

        logger.lifecycle("")
        logger.lifecycle("dbo console assembled: ${home.path}")
        logger.lifecycle("  start it:  ${home.path}/bin/karaf")
        logger.lifecycle("  then:      dbo-console:up")
        logger.lifecycle("  tenants:   ${tenantDir.path}  (*.json, reconciled every 2s)")
        logger.lifecycle("")
    }
}

// Deliberately NOT wired into `build`. Assembling unpacks a Karaf distribution,
// which no ordinary build needs — and since assembling refuses while a console
// is running, wiring it in would mean a running console broke the project build.


/**
 * Assembles the console WITHOUT Karaf's features service, on the distribution's
 * own logging.
 *
 * <p>Subtracting pax-logging from a feature-based Karaf does not converge. It
 * exports org.slf4j at 1.7 AND 2.0 at once and Karaf's plumbing is built across
 * both, so every boot feature reaches for it: `wrap` reinstalls it as a
 * dependency and undoes the substitution, and dropping `wrap` moves the failure
 * to management, then jaas, then the next one.
 *
 * <p>So the features service goes instead of pax-logging's dependents. The
 * startup set is written out directly — what a console needs and nothing else —
 * which is the minimal assembly the plan document has wanted throughout. The
 * bundles are the ones the `shell`, `bundle`, `jaas`, `ssh`, `system`,
 * `service`, `package` and `diagnostic` features name, resolved here at build
 * time rather than by a resolver at boot.
 */
fun installFeaturelessConsole(home: File) {
    val projectVersion = project.version.toString()
    val ours = mutableListOf<String>()

    // Startup bundles resolve from Karaf's own system/ repository, laid out the
    // Maven way, so anything this project supplies has to land at its path.
    val available = dboLoggingBundles.files + dboLegacySlf4j.files
    (loggingGavs.map {
        val (group, artifact, version) = it.split(":")
        Triple(group, artifact, version)
    } + listOf(
        Triple("cloud.jengu.dbo", "dbo-logging", projectVersion),
        Triple("cloud.jengu.dbo", "dbo-slf4j-compat", projectVersion))).forEach { (group, artifact, version) ->
        val fileName = "$artifact-$version.jar"
        val jar = available.firstOrNull { it.name == fileName }
            ?: throw GradleException("$fileName is not in the logging configuration")
        val into = home.resolve("system/${group.replace('.', '/')}/$artifact/$version/$fileName")
        into.parentFile.mkdirs()
        jar.copyTo(into, overwrite = true)
        ours.add("mvn:$group/$artifact/$version")
    }

    val k = karafVersion
    // Levels matter only in as much as logging must precede anything that logs
    // and the mvn: handler must precede anything resolved through it.
    val startup = linkedMapOf(
        4 to ours,
        5 to listOf("mvn:org.ops4j.pax.url/pax-url-aether/2.7.1"),
        8 to listOf(
            "mvn:org.fusesource.jansi/jansi/2.4.3",
            "mvn:org.jline/jline/3.30.9"),
        9 to listOf(
            "mvn:org.apache.felix/org.apache.felix.fileinstall/3.7.4",
            "mvn:org.osgi/org.osgi.util.function/1.2.0",
            "mvn:org.osgi/org.osgi.util.promise/1.3.0",
            "mvn:org.apache.felix/org.apache.felix.coordinator/1.0.2",
            "mvn:org.apache.felix/org.apache.felix.converter/1.0.14",
            "mvn:org.apache.felix/org.apache.felix.metatype/1.2.4"),
        10 to listOf("mvn:org.apache.felix/org.apache.felix.configadmin/1.9.26"),
        11 to listOf(
            "mvn:org.apache.felix/org.apache.felix.configurator/1.0.16",
            "mvn:org.apache.sling/org.apache.sling.commons.johnzon/1.2.16",
            "mvn:org.apache.felix/org.apache.felix.cm.json/1.0.8",
            "mvn:org.apache.felix/org.apache.felix.configadmin.plugin.interpolation/1.2.8",
            "mvn:org.apache.karaf.config/org.apache.karaf.config.core/$k"),
        // The console proper. jaas because the shell authenticates against it,
        // bundle.core because it owns the BundleWatcher dbo-console:watch drives.
        20 to listOf(
            // Karaf's login audit posts EventAdmin events and warns on every
            // login when there is nowhere to post them.
            "mvn:org.apache.karaf.services/org.apache.karaf.services.eventadmin/$k",
            "mvn:org.apache.karaf.jaas/org.apache.karaf.jaas.config/$k",
            "mvn:org.apache.karaf.jaas/org.apache.karaf.jaas.modules/$k",
            "mvn:org.apache.karaf.shell/org.apache.karaf.shell.core/$k",
            "mvn:org.apache.karaf.shell/org.apache.karaf.shell.commands/$k",
            "mvn:org.apache.karaf.bundle/org.apache.karaf.bundle.core/$k",
            "mvn:org.apache.karaf.system/org.apache.karaf.system.core/$k",
            "mvn:org.apache.karaf.service/org.apache.karaf.service.core/$k",
            "mvn:org.apache.karaf.package/org.apache.karaf.package.core/$k",
            "mvn:org.bouncycastle/bcprov-jdk18on/1.84",
            "mvn:org.bouncycastle/bcutil-jdk18on/1.84",
            "mvn:org.bouncycastle/bcpkix-jdk18on/1.84",
            "mvn:org.apache.sshd/sshd-osgi/2.17.1",
            "mvn:org.apache.sshd/sshd-scp/2.17.1",
            "mvn:org.apache.sshd/sshd-sftp/2.17.1",
            "mvn:org.apache.karaf.shell/org.apache.karaf.shell.ssh/$k"),
    )

    home.resolve("etc/startup.properties").writeText(
        buildString {
            appendLine("# Generated by :karaf:console. The features service is not here:")
            appendLine("# this is the whole runtime, in start-level order.")
            startup.forEach { (level, coordinates) ->
                coordinates.forEach { appendLine("${it.replace(":", "\\:")} = $level") }
            }
        }
    )

    // Nothing reads these without the features service, and leaving them would
    // suggest a boot list that no longer boots anything.
    home.resolve("etc/org.apache.karaf.features.cfg").delete()

    logger.lifecycle("dbo: featureless console on the distribution's logging "
        + "(no features service, no log:set — one global level from dbo.log.level)")
}
