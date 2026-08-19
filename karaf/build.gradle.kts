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
val legacySlf4jApi = "org.slf4j:slf4j-api:1.7.36"

// The 1.7 API declares a MANDATORY import of org.slf4j.impl — that generation
// bound by classpath convention rather than by ServiceLoader — so it does not
// resolve without a binding at all. The no-op one is the right binding here:
// pax-url's logging should be silent, not wrong, and nothing else is meant to
// wire to 1.7.
val legacySlf4jBinding = "org.slf4j:slf4j-nop:1.7.36"

val karafDist: Configuration by configurations.creating
val dboLoggingBundles: Configuration by configurations.creating
// Its own configuration, because Gradle resolves two versions of one module to
// the highest and would quietly drop the 1.7 API — the one bundle here whose
// whole purpose is being the older generation.
val dboLegacySlf4j: Configuration by configurations.creating

@Suppress("UNCHECKED_CAST")
val loggingGavs = (rootProject.extra["dboLoggingBundles"] as List<String>) + spiFlyExtension + logServiceApi

dependencies {
    karafDist("org.apache.karaf:apache-karaf:$karafVersion@tar.gz")
    loggingGavs.forEach { dboLoggingBundles(it) { isTransitive = false } }
    listOf(legacySlf4jApi, legacySlf4jBinding).forEach {
        dboLegacySlf4j(it) { isTransitive = false }
    }
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
    dependsOn(karafHome, ":karaf:commands:jar")

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
            installDboLogging(home)
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
 * Replaces Karaf's logging with the one the distribution ships.
 *
 * <p>Three places name it, and all three have to agree or the bundles arrive
 * twice: the startup set the launcher installs, the `framework` feature that
 * defines the base runtime, and the boot feature list — `log` is Karaf's own
 * log commands, which are pax-logging's and have nothing to bind to once it is
 * gone.
 */
fun installDboLogging(home: File) {
    val coordinates = mutableListOf<String>()
    val projectVersion = project.version.toString()

    // Startup bundles resolve from Karaf's own system/ repository, laid out
    // the Maven way, so each jar has to land at its coordinate's path.
    val available = dboLoggingBundles.files + dboLegacySlf4j.files
    ((loggingGavs + legacySlf4jApi + legacySlf4jBinding).map {
        val (group, artifact, version) = it.split(":")
        Triple(group, artifact, version)
    } + listOf(Triple("cloud.jengu.dbo", "dbo-logging", projectVersion))).forEach { (group, artifact, version) ->
        val fileName = "$artifact-$version.jar"
        val jar = available.firstOrNull { it.name == fileName }
            ?: throw GradleException("$fileName is not in the logging configuration")
        val into = home.resolve(
            "system/${group.replace('.', '/')}/$artifact/$version/$fileName")
        into.parentFile.mkdirs()
        jar.copyTo(into, overwrite = true)
        coordinates.add("mvn:$group/$artifact/$version")
    }

    // The launcher's startup set. Level 8 is where pax-logging sat: before
    // anything that logs, after the framework's own plumbing.
    val startup = home.resolve("etc/startup.properties")
    val kept = startup.readLines().filterNot { it.contains("pax-logging") }
    startup.writeText(
        (kept + coordinates.map { "${it.replace(":", "\\:")} = 8" }).joinToString("\n") + "\n"
    )

    // The framework feature, which would otherwise install pax-logging again
    // the moment features come up. Only the first occurrences: the same pair
    // appears further down under framework-logback, which nothing boots.
    val features = home.resolve(
        "system/org/apache/karaf/features/framework/$karafVersion/framework-$karafVersion-features.xml")
    var xml = features.readText()
    val paxLine = Regex("""\s*<bundle start-level="8">mvn:org\.ops4j\.pax\.logging/pax-logging-api/[^<]*</bundle>""")
    val paxImpl = Regex("""\s*<bundle start-level="8">mvn:org\.ops4j\.pax\.logging/pax-logging-log4j2/[^<]*</bundle>""")
    xml = paxLine.replaceFirst(xml, coordinates.joinToString("") {
        "\n        <bundle start-level=\"8\">$it</bundle>"
    })
    xml = paxImpl.replaceFirst(xml, "")
    features.writeText(xml)

    // Karaf's log commands are pax-logging's.
    val featuresCfg = home.resolve("etc/org.apache.karaf.features.cfg")
    featuresCfg.writeText(
        featuresCfg.readText().replace("    log/$karafVersion, \\\n", "")
    )

    logger.lifecycle("dbo: logging posture = dbo (the distribution's binding; "
        + "no log:set, no log:tail, one global level from dbo.log.level)")
}
