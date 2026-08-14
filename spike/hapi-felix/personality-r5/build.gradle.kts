// The R5 personality bundle: HAPI R5 structures only (parse + FHIRPath)
// as private nested jars. Exports nothing HAPI — only the activator wiring
// against io.dbo.spike.fhir.api.

val embedded: Configuration by configurations.creating
configurations.compileOnly.get().extendsFrom(embedded)

dependencies {
    embedded("ca.uhn.hapi.fhir:hapi-fhir-structures-r5:8.10.1")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-validation:8.10.1")
    embedded("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r5:8.10.1")


    embedded("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:8.10.1")
    compileOnly("org.osgi:osgi.core:8.0.0")
    compileOnly(project(":api"))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into("lib") { from(embedded) }
    doFirst {
        val libs = embedded.resolve().joinToString(",") { "lib/${it.name}" }
        manifest {
            attributes(
                "Bundle-ManifestVersion" to "2",
                "Bundle-SymbolicName" to "io.dbo.spike.personality.r5",
                "Bundle-Version" to "0.0.1",
                "Bundle-Activator" to "io.dbo.spike.r5.Activator",
                "Bundle-ClassPath" to ".,$libs",
                "Import-Package" to listOf(
                    "org.osgi.framework",
                    "io.dbo.spike.fhir.api",
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
