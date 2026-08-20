import java.security.MessageDigest
import java.util.jar.JarFile

// The element-model face and the definitions it serves from.
//
// A face is nothing without the definitions it validates and extracts
// against, and a store must not fetch them: no network call at bring-up, no
// writable cache outside its own state, and no build whose output depends on
// what a registry served that day. So they are pinned here, verified by
// digest, and embedded in the bundle
// (REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE).

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
    // the HL7 engine: this bundle imports it, one exporter for the framework
    api(project(":core:dbo-fhir-stack"))
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    compileOnly("org.osgi:osgi.core:8.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // the engine logs; compileOnly above is the runtime's arrangement, and a
    // test has no bundle to import it from
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * One FHIR definition package this face carries.
 *
 * The digest is the point: a package is fetched once at build time from a
 * registry that serves mutable names, and a build that took whatever came back
 * would be a build nobody can reproduce. A mismatch fails the build rather than
 * shipping definitions nobody chose.
 */
data class Definitions(
    val fhirVersion: String,
    val name: String,
    val version: String,
    val sha256: String,
    /** false while a package is understood but not yet chosen — see the epic. */
    val carried: Boolean = true,
    /**
     * Whether this bundle announces itself as the face for that version.
     *
     * Carrying a version's definitions and owning its face are different
     * things: R4's definitions are carried so that `dbo-fhir-r4` can serve
     * through this facade while keeping the halves that are genuinely R4's —
     * terminology and subscriptions. Announcing it here as well would register
     * two faces under one code, and which one served a tenant would depend on
     * ordering.
     */
    val announced: Boolean = true,
)

// Pinned. A ballot moves with the core's version codes (#59), so these two
// numbers are changed together and never one of them.
val definitions = listOf(
    // R4 — the version most of the world speaks, and the one with an incumbent
    // to match rather than a promise to make.
    Definitions("r4", "hl7.fhir.r4.core", "4.0.1",
        "b090bf929e1f665cf2c91583720849695bc38d2892a7c5037c56cb00817fb091", announced = false),
    Definitions("r4", "hl7.terminology.r4", "7.3.0",
        "1a1ef2aa22ecc820341267f2bdba3b2d1f4adafb9bdddfc9e51c611cd64f3b54", announced = false),
    Definitions("r4", "hl7.fhir.uv.tools.r4", "1.1.2",
        "a1f166f8808629a40c4acabc16a4fbfd164d9f38f9db95b6f4b38bb69155dfe4", announced = false),
    // R5 — the version the store's own converters hop to, and the last of the
    // three to move onto this facade.
    Definitions("r5", "hl7.fhir.r5.core", "5.0.0",
        "74b27cd1bfce9e80eaceac431edf230b0945a443564fbf5512f82e5fa50a80d4", announced = false),
    Definitions("r5", "hl7.terminology.r5", "7.3.0",
        "c2ee6bccc9d0130d0a90db5967e7adbf9dd175df7a3351c531642c3dce102d10", announced = false),
    Definitions("r5", "hl7.fhir.uv.tools.r5", "1.1.2",
        "fcdcec5e65283969a2073a8ab8bc3ef18dc7dcb16e716a7cc49ea5dfbff83f60", announced = false),
    Definitions("r6", "hl7.fhir.r6.core", "6.0.0-ballot5",
        "dbea14a39ebbcbaec53fe7cfb805048bf1aed023384e43c9f070c9c6cfd705b0"),
    // The terminology a version's value sets bind to, and the tooling
    // extensions its own definitions carry.
    Definitions("r6", "hl7.terminology.r5", "7.3.0",
        "c2ee6bccc9d0130d0a90db5967e7adbf9dd175df7a3351c531642c3dce102d10"),
    Definitions("r6", "hl7.fhir.uv.tools.r5", "1.1.2",
        "fcdcec5e65283969a2073a8ab8bc3ef18dc7dcb16e716a7cc49ea5dfbff83f60"),
    // Not carried yet, and listed rather than forgotten: it resolves most of
    // what is otherwise an unknown extension, and it is R5-flavoured, so it
    // also disagrees with R6 about an element's type — which would refuse a
    // write rather than warn about it. Turning it on is one word here, and it
    // is a decision with evidence attached, not a default.
    Definitions("r6", "hl7.fhir.uv.extensions.r5", "5.3.0",
        "f1039cac888d79ebd29878d7debe5e647ffe7ed962a9033da095258a03a06105", carried = false),
)

val definitionsDir = layout.buildDirectory.dir("definitions")

val fetchDefinitions by tasks.registering {
    description = "Fetches the pinned FHIR definition packages and verifies their digests."
    val carried = definitions.filter { it.carried }
    inputs.property("packages",
        carried.map { "${it.name}#${it.version}:${it.sha256}:${it.announced}" })
    outputs.dir(definitionsDir)
    doLast {
        val out = definitionsDir.get().asFile
        out.mkdirs()
        val index = StringBuilder()
        carried.forEach { pkg ->
            val file = File(out, "${pkg.name}-${pkg.version}.tgz")
            if (!file.exists() || digest(file) != pkg.sha256) {
                logger.lifecycle("fetching ${pkg.name}#${pkg.version}")
                uri("https://packages2.fhir.org/web/${file.name}").toURL().openStream()
                    .use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val actual = digest(file)
            if (actual != pkg.sha256) {
                file.delete()
                error("${pkg.name}#${pkg.version}: expected sha256 ${pkg.sha256}, got $actual — " +
                    "the registry served something other than what this build pins")
            }
            index.append("${pkg.fhirVersion}|${pkg.name}|${pkg.version}|${file.name}" +
                "|${pkg.announced}\n")
        }
        File(out, "index").writeText(index.toString())
        logger.lifecycle("definitions: ${carried.size} packages, " +
            "${out.listFiles()!!.sumOf { it.length() } / 1024 / 1024} MB")
    }
}

fun digest(file: File): String {
    val sha = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sha.update(buffer, 0, read)
        }
    }
    return sha.digest().joinToString("") { "%02x".format(it) }
}

// Under definitions/ rather than at the root: the bundle's resources are its
// own namespace, and a package tarball landing beside a class file is how two
// modules come to disagree about whose "index" it is.
tasks.named<ProcessResources>("processResources") {
    dependsOn(fetchDefinitions)
    from(definitionsDir) { into("definitions") }
    // How a version is discovered where there is no service registry
    from("src/main/resources-services")
}

val stackJar = project(":core:dbo-fhir-stack").tasks.named<Jar>("jar")

/**
 * Every engine package the shared bundle exports, imported wholesale — the
 * same reasoning as the personalities: this face reaches HL7 types it never
 * names, and a missing entry resolves and then throws on first use.
 */
fun engineImports(): List<String> =
    JarFile(stackJar.get().archiveFile.get().asFile).use { jar ->
        jar.manifest.mainAttributes.getValue("Export-Package").split(",")
    }

tasks.jar {
    dependsOn(stackJar)
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    doFirst {
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.element",
                // The bundle announces every version it carries; see Activator.
                "Bundle-Activator" to "cloud.jengu.dbo.fhir.element.Activator",
                "Bundle-Version" to project.version.toString().replace("-", "."),
                "Export-Package" to "cloud.jengu.dbo.fhir.element;version=\"0.1.0\"",
                "Import-Package" to (listOf(
                    "org.slf4j",
                    "org.osgi.framework;version=\"[1.8,2)\"",
                ) + engineImports() + listOf(
                    "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.face;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.api.feed;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.common;version=\"[0.1,1)\"",
                )).joinToString(","),
            )
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // A version's definitions are tens of megabytes of JSON parsed into a
    // context; the default worker heap is not enough to hold one.
    // Every carried version's definitions can be resident at once: the gate
    // holds all three to the same claim in one run.
    maxHeapSize = "4g"
}
