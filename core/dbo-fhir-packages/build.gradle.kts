import java.security.MessageDigest
import java.util.jar.JarFile

// The definition packages a face root reads, as a FRAGMENT of the face.
//
// A fragment because the packages are resources and nothing else: attached,
// they appear on the host's own classpath at the path the host already looks
// in, so the face needs no second mechanism to find them and no service to
// ask. Absent, the host resolves and starts exactly as before and finds
// nothing — which is the whole of item 025's last move. A node that does not
// install this CANNOT populate a worker context, whatever classes it holds,
// because SimpleWorkerContext with nothing to load is a class and not a graph.
//
// Which node installs it is a deployment decision: a face root reads a
// version's packages once and publishes it as records, and every other tenant
// takes it from those records.

plugins {
    id("biz.aQute.bnd.builder")
}

base { archivesName.set("dbo-fhir-packages") }

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

// Pinned. A ballot moves with the core's version codes, so these two
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
val indexDir = layout.buildDirectory.dir("definitions-index")

/**
 * What faces exist, without a byte of what they are made of.
 *
 * <p>Which versions this runtime serves was read off the tarballs on the
 * classpath, so a node that shipped none registered no face and served
 * nobody — the whole runtime disappearing because its resources did. The
 * index is the metadata and it is small, so it travels with the FACE and
 * the packages travel with this fragment. A node without the fragment
 * announces the same versions and simply has nothing to load a context
 * from, which is a refusal it can name.
 *
 * <p>Derived from the pins rather than from the downloads, so a build that
 * has not fetched anything still knows what it would fetch.
 */
val definitionsIndex = tasks.register("definitionsIndex") {
    description = "Writes the index of pinned packages — names and versions, no payload."
    val carried = definitions.filter { it.carried }
    inputs.property("packages",
        carried.map { "${it.name}#${it.version}:${it.announced}" })
    outputs.dir(indexDir)
    doLast {
        val out = indexDir.get().asFile
        out.mkdirs()
        File(out, "index").writeText(carried.joinToString("") { pkg ->
            "${pkg.fhirVersion}|${pkg.name}|${pkg.version}|" +
                "${pkg.name}-${pkg.version}.tgz|${pkg.announced}\n"
        })
    }
}

// Consumed by the face bundle, which ships the index while this fragment
// ships the packages it names.
configurations.create("definitionsIndexFiles") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add("definitionsIndexFiles", indexDir) { builtBy(definitionsIndex) }

val fetchDefinitions = tasks.register("fetchDefinitions") {
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


// The same path the host looks in, because a fragment's resources are found
// through the HOST's classloader: /definitions/index and the tarballs beside
// it. Naming it differently here would be a second pin to keep in agreement.
tasks.named<ProcessResources>("processResources") {
    dependsOn(fetchDefinitions)
    from(definitionsDir) {
        into("definitions")
        // The index is the FACE's, so that a node without this fragment still
        // knows which versions exist. Shipping it here too would put two
        // copies on one classpath and leave which one wins to the resolver.
        exclude("index")
    }
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.packages",
            "Fragment-Host" to "cloud.jengu.dbo.fhir.element",
        ))
    }
}
