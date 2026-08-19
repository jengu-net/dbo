import org.gradle.api.artifacts.ResolvedArtifact
import java.util.zip.ZipFile

// The HL7/HAPI stack, embedded ONCE and exported. Every personality used to
// carry its own copy: 74 of the ~76 jars were byte-identical between R4 and R5,
// 140 MB duplicated verbatim, and Felix copies each installed bundle into the
// framework's own cache — so the duplication multiplied per framework instance
// rather than being amortised.
//
// Slimming the stack per personality is not the alternative it looks like: the
// HL7 validator is an R5 engine that converts input up to R5, validates there
// and maps back, so r4/r4b/dstu3/dstu2 are its input adapters rather than spare
// parts. One indivisible version-agnostic engine is exactly what belongs in a
// shared bundle.
//
// What stays per personality: hapi-fhir-validation-resources-rX, the profile
// and value-set definitions of one version.

val embedded: Configuration by configurations.creating

// Consumers on a plain classpath (the harness, the bench, dbo-tenant) get HAPI
// from here transitively; consumers inside Felix get it by Import-Package from
// this bundle. One dependency declaration, both paths.
configurations.api.get().extendsFrom(embedded)

dependencies {
    // hapi-fhir-validation drags in the whole engine, structures-r5 included;
    // structures-r4 is the one adapter it does not pull for itself.
    embedded("ca.uhn.hapi.fhir:hapi-fhir-validation:8.10.1")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.10.1")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:8.10.1")
    // slf4j-api is SHARED, not embedded: one binding for the whole
    // runtime instead of a private one per bundle. compileOnly because
    // it resolves from the slf4j-api bundle at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.18")
}

// The HL7 core stack carries the spec-AUTHORING side of that library as well
// as the validating side: a UML renderer, a package-cache database, an XSLT
// engine and a git client arrive as transitives of org.hl7.fhir.*. A store
// validating a resource needs none of them.
//
// These fail at RUNTIME, not at build — a Class.forName behind an authoring
// entry point resolves fine until something calls it — so the proof is the
// suite exercising validation, conversion and terminology ingestion, not a
// green compile.
configurations.named("embedded") {
    // The HL7 core's version is pinned for every module in the root build:
    // this bundle embeds it, and everything compiling against the bundle
    // resolves it too, so the two must not be pinned in different places.
    // slf4j-api arrives as a transitive of the HL7 core stack and must NOT ride
    // along: this bundle imports `org.slf4j` from the shared slf4j-api bundle,
    // and an embedded copy beside it splits the API in two — `org.slf4j` from
    // the import, `org.slf4j.spi` from lib/ — which surfaces as a LinkageError
    // (loader constraint violation) the first time something logs, not as a
    // resolution failure.
    exclude(group = "org.slf4j")
    exclude(group = "net.sourceforge.plantuml")
    exclude(group = "org.xerial", module = "sqlite-jdbc")
    exclude(group = "net.sf.saxon", module = "Saxon-HE")
    exclude(group = "org.eclipse.jgit")
}

/**
 * Every FHIR package that actually carries classes, read off the jars rather
 * than listed by hand. org.hl7.fhir.* spans dozens of jars; a hand-written
 * export list resolves and then fails on first use of whatever it forgot.
 *
 * **Each export carries the version of the jar it came from.** A package
 * exported without one is exported at 0.0.0, and any consumer stating a range
 * — which bnd computes by default, so most of them do — then fails to resolve
 * against this bundle. A host platform's driver SPI importing
 * `org.hl7.fhir.r4.model;version="[6.9,7)"` is the case that found this:
 * unversioned, this bundle cannot satisfy it, and the framework where the
 * store owns the FHIR classes does not come up at all (#47).
 *
 * The two families are NOT one number: `ca.uhn.fhir.*` is HAPI's own version
 * while `org.hl7.fhir.*` is the HL7 core family HAPI ships (8.10.1 carries
 * 6.9.x). Taking each package's version from its own artifact keeps that true
 * without anybody having to remember it.
 */
fun exportedPackages(artifacts: Iterable<ResolvedArtifact>): List<String> {
    val versions = sortedMapOf<String, String>()
    val conflicts = sortedMapOf<String, MutableSet<String>>()
    artifacts.forEach { artifact ->
        val version = osgiVersion(artifact.moduleVersion.id.version)
        ZipFile(artifact.file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .forEach { entry ->
                    val pkg = entry.name.substringBeforeLast('/', "").replace('/', '.')
                    if (pkg.startsWith("org.hl7.fhir") || pkg.startsWith("ca.uhn.fhir")) {
                        val existing = versions.putIfAbsent(pkg, version)
                        if (existing != null && existing != version) {
                            conflicts.getOrPut(pkg) { sortedSetOf(existing) } += version
                        }
                    }
                }
        }
    }
    // An export with no version is exported at 0.0.0 and satisfies no stated
    // range. Checked rather than trusted, because the symptom appears in a
    // consumer's framework and names this bundle only by its absence.
    val unversioned = versions.filterValues { it.isBlank() }.keys
    if (unversioned.isNotEmpty()) {
        error("dbo-fhir-stack: package(s) would be exported without a version: " +
            unversioned.joinToString(", "))
    }
    // A package arriving at two versions is a split package, and picking one
    // silently exports classes the version does not describe.
    if (conflicts.isNotEmpty()) {
        error("dbo-fhir-stack: package(s) present at more than one version — " +
            conflicts.entries.joinToString("; ") { "${it.key} at ${it.value.joinToString(", ")}" })
    }
    return versions.map { (pkg, version) -> "$pkg;version=\"$version\"" }
}

/**
 * `1.2.3-SNAPSHOT` is a valid Maven version and not a valid OSGi one; the
 * qualifier is separated by a dot, and only after three numeric segments.
 */
fun osgiVersion(mavenVersion: String): String {
    val parts = mavenVersion.split("-", limit = 2)
    val numeric = parts[0].split(".").let { it + List(maxOf(0, 3 - it.size)) { "0" } }
    val base = numeric.take(3).joinToString(".") { it.toIntOrNull()?.toString() ?: "0" }
    return if (parts.size == 2) "$base.${parts[1].replace(Regex("[^A-Za-z0-9_-]"), "_")}" else base
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into("lib") { from(embedded) }
    // Whose code rides in this jar, and under what terms. A recipient of
    // the artifact has the artifact, not the repository.
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    doFirst {
        val artifacts = embedded.resolvedConfiguration.resolvedArtifacts
        val jars = artifacts.map { it.file }
        val libs = jars.joinToString(",") { "lib/${it.name}" }
        val exports = exportedPackages(artifacts)
        logger.lifecycle("dbo-fhir-stack: ${jars.size} embedded jars, ${exports.size} exported FHIR packages")
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.stack",
                "Bundle-Version" to project.version.toString().replace("-", "."),
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to exports.joinToString(","),
                "Import-Package" to listOf(
                    // The whole slf4j API, not just `org.slf4j`: the engine
                    // reaches the fluent builder in org.slf4j.spi the first
                    // time HAPI logs, and importing one package of a library
                    // whose classes reference the others fails at that call
                    // rather than at resolution.
                    "org.slf4j",
                    "org.slf4j.spi",
                    "org.slf4j.event",
                    "org.slf4j.helpers",
                    "javax.naming;resolution:=optional",
                    "javax.naming.spi;resolution:=optional",
                    "javax.management;resolution:=optional",
                    "javax.net;resolution:=optional",
                    "javax.net.ssl;resolution:=optional",
                    "javax.crypto;resolution:=optional",
                    "javax.crypto.spec;resolution:=optional",
                    "javax.security.auth;resolution:=optional",
                    "javax.security.auth.callback;resolution:=optional",
                    "javax.security.auth.x500;resolution:=optional",
                    "javax.security.sasl;resolution:=optional",
                    "javax.sql;resolution:=optional",
                    "javax.xml.parsers;resolution:=optional",
                    "javax.xml.stream;resolution:=optional",
                    "javax.xml.stream.events;resolution:=optional",
                    "javax.xml.stream.util;resolution:=optional",
                    "javax.xml.transform;resolution:=optional",
                    "javax.xml.transform.dom;resolution:=optional",
                    "javax.xml.transform.sax;resolution:=optional",
                    "javax.xml.transform.stax;resolution:=optional",
                    "javax.xml.transform.stream;resolution:=optional",
                    "javax.xml.validation;resolution:=optional",
                    "javax.xml.namespace;resolution:=optional",
                    "javax.xml.datatype;resolution:=optional",
                    "javax.xml.xpath;resolution:=optional",
                    "org.xml.sax;resolution:=optional",
                    "org.xml.sax.helpers;resolution:=optional",
                    "org.xml.sax.ext;resolution:=optional",
                    "org.w3c.dom;resolution:=optional",
                    "org.w3c.dom.ls;resolution:=optional",
                    "org.w3c.dom.events;resolution:=optional",
                    "org.ietf.jgss;resolution:=optional",
                ).joinToString(","),
                // The validator loads profile definitions out of
                // org.hl7.fhir.rX.model.* resource directories that live in
                // whichever personality is installed. Those cannot be imported
                // statically: this bundle has to resolve with no personality
                // present, and with any number of them.
                "DynamicImport-Package" to "org.hl7.fhir.*",
            )
        }
    }
}
