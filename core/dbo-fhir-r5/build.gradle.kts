import java.util.jar.JarFile
import java.util.zip.ZipFile

// The FHIR R5 personality — deliberate mechanical port of dbo-fhir-r4 with
// r5 model imports: per-personality compiled units are the target shape.
// A neutral-API commons (FhirTerser-based) is the known refactor option,
// deferred until the R6 ballot personality makes the cost real.
//
// The engine is NOT embedded: it is one shared bundle
// (:core:dbo-fhir-stack) that this personality imports from. What stays here is
// R5's own validation resources — the profiles and value sets of one version.

val embedded: Configuration by configurations.creating
configurations.implementation.get().extendsFrom(embedded)

val hapi = rootProject.extra["hapiVersion"] as String

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
    api(project(":core:dbo-subscriptions"))
    api(project(":core:dbo-terminology"))
    // HAPI on the compile classpath, and at runtime the one bundle that owns it
    api(project(":core:dbo-fhir-stack"))
    // the shared facade: reading, framing, extracting and serving are one
    // implementation for every version now
    api(project(":core:dbo-fhir-element"))
    // slf4j-api is SHARED, not embedded: one binding for the whole
    // runtime instead of a private one per bundle. compileOnly because
    // it resolves from the slf4j-api bundle at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    // the registry the face announces itself to; the framework provides it
    compileOnly("org.osgi:osgi.core:8.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r5:$hapi") {
        isTransitive = false
    }
}

/**
 * The resource directories this personality contributes back to the engine.
 * hapi-fhir-validation-resources-rX carries no classes at all — profiles, value
 * sets and schemas under org/hl7/fhir/rX/model/ — and the validator in the
 * shared bundle reaches them through its DynamicImport-Package. Read off the
 * jar rather than listed by hand, so a HAPI upgrade that adds a directory does
 * not silently stop exporting it.
 */
fun resourcePackages(jars: Iterable<File>): List<String> {
    val packages = sortedSetOf<String>()
    jars.forEach { jar ->
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("org/hl7/fhir/") }
                .forEach { packages += it.name.substringBeforeLast('/').replace('/', '.') }
        }
    }
    return packages.toList()
}

val stackJar = project(":core:dbo-fhir-stack").tasks.named<Jar>("jar")

/**
 * Every engine package the shared bundle exports, imported wholesale.
 *
 * Not a hand-picked list: this personality's classes reach HAPI types they never
 * name — {@code ctx.getVersion()} returns a {@code ca.uhn.fhir.model.api}
 * interface — and a missing entry RESOLVES and then throws
 * NoClassDefFoundError on first use, with no build-time signal. Naming the one
 * exporter's export list keeps the two sides of the boundary the same set by
 * construction.
 */
fun engineImports(): List<String> =
    JarFile(stackJar.get().archiveFile.get().asFile).use { jar ->
        jar.manifest.mainAttributes.getValue("Export-Package").split(",")
    }

tasks.jar {
    dependsOn(stackJar)
    // And it is an INPUT, not only an order: this manifest is computed FROM
    // the stack's exports, so a change there has to rebuild this jar. Ordered
    // but not declared, Gradle called this task up to date after the stack's
    // exports changed underneath it — and the bundle then asked the container
    // for a package nothing exports any more, which fails at bring-up in a
    // test nobody would connect to a build-cache decision (#32).
    inputs.file(stackJar.get().archiveFile)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into("lib") { from(embedded) }
    // Whose code rides in this jar, and under what terms. A recipient of
    // the artifact has the artifact, not the repository.
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    doFirst {
        val libs = embedded.resolve().joinToString(",") { "lib/${it.name}" }
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.r5",
                // The bundle announces the version it serves; see Activator.
                "Bundle-Activator" to "cloud.jengu.dbo.fhir.r5.Activator",
                "Bundle-Version" to project.version.toString().replace("-", "."),
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to (listOf("cloud.jengu.dbo.fhir.r5;version=\"0.1.0\"")
                    + resourcePackages(embedded.resolve())).joinToString(","),
                "Import-Package" to (listOf(
                    "org.slf4j",
                    "org.osgi.framework;version=\"[1.8,2)\"",
                ) + engineImports() + listOf(
                    "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
                    // the inward contract: what this face implements for the
                    // engine, as against core.api which is what it calls
                    "cloud.jengu.dbo.core.face;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.process;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.api.feed;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.common;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.element;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.subscriptions;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.terminology;version=\"[0.1,1)\"",
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
                )).joinToString(","),
            )
        }
    }
}

// The R5 core package loads eagerly; a default heap dies as HAPI-2330 with a
// null message, three frames above an OutOfMemoryError nobody sees.
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}
