// The FHIR R5 personality — deliberate mechanical port of dbo-fhir-r4 with
// r5 model imports: under the §7.3 packaging model each personality embeds a
// PRIVATE HAPI stack, so per-personality compiled units are the target shape.
// A neutral-API commons (FhirTerser-based) is the known refactor option,
// deferred until the R6 ballot personality makes the cost real (dbo#10).

val embedded: Configuration by configurations.creating
configurations.implementation.get().extendsFrom(embedded)

dependencies {
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
    api(project(":core:dbo-subscriptions"))
    embedded("ca.uhn.hapi.fhir:hapi-fhir-structures-r5:8.10.1")
    embedded("org.slf4j:slf4j-simple:2.0.18")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-validation:8.10.1")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r5:8.10.1")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:8.10.1")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into("lib") { from(embedded) }
    doFirst {
        val libs = embedded.resolve().joinToString(",") { "lib/${it.name}" }
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.r5",
                "Bundle-Version" to project.version.toString().replace("-", "."),
                "Bundle-ClassPath" to ".,$libs",
                "Export-Package" to "cloud.jengu.dbo.fhir.r5;version=\"0.1.0\"",
                "Import-Package" to listOf(
                    "cloud.jengu.dbo.core.api;version=\"[0.1,1)\"",
                    // the inward contract: what this face implements for the
                    // engine, as against core.api which is what it calls
                    "cloud.jengu.dbo.core.face;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core.api.feed;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.core;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.fhir.common;version=\"[0.1,1)\"",
                    "cloud.jengu.dbo.subscriptions;version=\"[0.1,1)\"",
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
