// The FHIR R4 personality: everything that knows what an R4 payload MEANS.
// HAPI rides as ordinary dependencies here; the §7.3 boundary (no HAPI type
// crosses the public API) is enforced by ApiBoundaryTest in the harness, and
// the OSGi private-embedding packaging is the proven pattern applied in
// a later packaging task.

val embedded: Configuration by configurations.creating
configurations.implementation.get().extendsFrom(embedded)

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
    api(project(":core:dbo-subscriptions"))
    api(project(":core:dbo-terminology"))
    embedded("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.10.1")
    // slf4j-api is SHARED, not embedded: one binding for the whole
    // runtime instead of a private one per bundle. compileOnly because
    // it resolves from the slf4j-api bundle at runtime.
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-validation:8.10.1")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r4:8.10.1")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:8.10.1")
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

tasks.jar {
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
                "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.r4",
                "Bundle-Version" to project.version.toString().replace("-", "."),
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to "cloud.jengu.dbo.fhir.r4;version=\"0.1.0\"",
                "Import-Package" to listOf(
                    "org.slf4j",
                    "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
                    // the inward contract: what this face implements for the
                    // engine, as against core.api which is what it calls
                    "cloud.jengu.dbo.core.face;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.api.feed;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.common;version=\"[0.1,1)\"",
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
                ).joinToString(","),
            )
        }
    }
}
