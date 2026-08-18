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
    exclude(group = "net.sourceforge.plantuml")
    exclude(group = "org.xerial", module = "sqlite-jdbc")
    exclude(group = "net.sf.saxon", module = "Saxon-HE")
    exclude(group = "org.eclipse.jgit")
}

/**
 * Every FHIR package that actually carries classes, read off the jars rather
 * than listed by hand. org.hl7.fhir.* spans dozens of jars; a hand-written
 * export list resolves and then fails on first use of whatever it forgot.
 */
fun exportedPackages(jars: Iterable<File>): List<String> {
    val packages = sortedSetOf<String>()
    jars.forEach { jar ->
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .forEach { entry ->
                    val pkg = entry.name.substringBeforeLast('/', "").replace('/', '.')
                    if (pkg.startsWith("org.hl7.fhir") || pkg.startsWith("ca.uhn.fhir")) {
                        packages += pkg
                    }
                }
        }
    }
    return packages.toList()
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into("lib") { from(embedded) }
    // Whose code rides in this jar, and under what terms. A recipient of
    // the artifact has the artifact, not the repository.
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    doFirst {
        val jars = embedded.resolve()
        val libs = jars.joinToString(",") { "lib/${it.name}" }
        val exports = exportedPackages(jars)
        logger.lifecycle("dbo-fhir-stack: ${jars.size} embedded jars, ${exports.size} exported FHIR packages")
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.stack",
                "Bundle-Version" to project.version.toString().replace("-", "."),
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to exports.joinToString(","),
                "Import-Package" to listOf(
                    "org.slf4j",
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
