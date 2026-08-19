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

val karafVersion = (findProperty("dbo.karaf.version") as String?) ?: "4.4.11"

val karafDist: Configuration by configurations.creating

dependencies {
    karafDist("org.apache.karaf:apache-karaf:$karafVersion@tar.gz")
}

@Suppress("UNCHECKED_CAST")
val runtimeModules = rootProject.extra["dboRuntimeModules"] as List<String>
@Suppress("UNCHECKED_CAST")
val runtimeExternal = rootProject.extra["dboRuntimeExternalBundles"] as List<String>

// The fat bundles carry their private stacks as nested jars — the HL7/HAPI
// engine, DBOS — and rebuild slowly for changes that are almost never in them.
// Installed like everything else, just not watched.
val notWatched = setOf("dbo-fhir-stack", "dbo-subscriptions")

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

// Stock Karaf, unpacked. The tarball's single top-level directory is stripped
// so the console lands at a predictable path.
val karafHome = tasks.register<Sync>("karafHome") {
    description = "Unpacks a stock Karaf for the development console."
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
    dependsOn(karafHome)

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
        val coordinates = runtimeModules.map(::moduleCoordinate) +
            runtimeExternal.map(::externalCoordinate)
        val watched = runtimeModules
            .filter { it.substringAfterLast(':') !in notWatched }
            .map(::moduleCoordinate)

        home.resolve("dbo.karaf").writeText(
            buildString {
                appendLine("# The dbo development console. Re-runnable: installing an")
                appendLine("# already-installed location returns the existing bundle.")
                appendLine("#   karaf@root()> shell:source dbo.karaf")
                appendLine()
                appendLine("bundle:install -s " + coordinates.joinToString(" "))
                appendLine()
                appendLine("# Watched: the thin bundles, where the work happens. The fat ones")
                appendLine("# (${notWatched.joinToString(", ")}) carry embedded stacks and are")
                appendLine("# installed but not watched.")
                appendLine("bundle:watch " + watched.joinToString(" "))
                appendLine()
                appendLine("bundle:list")
            }
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
        logger.lifecycle("  then:      shell:source dbo.karaf")
        logger.lifecycle("  tenants:   ${tenantDir.path}  (*.json, reconciled every 2s)")
        logger.lifecycle("")
    }
}

tasks.named("build") { dependsOn(console) }
