plugins {
    `java-platform`
}

// What a consumer of dbo compiles against, published as an artifact rather
// than left as a fact people remember.
//
// dbo's runtime supplies the FHIR classes: the shared stack embeds HAPI and
// the HL7 core and exports them, and in a framework where the store owns those
// classes, everything else compiles against the same ones or does not wire.
// Which means dbo is the source of truth for the numbers, and a consumer that
// restates them is agreeing by coincidence — the coincidence holds until one
// side bumps, and then the failure is a resolution error in an appliance
// rather than a build error in CI.
//
// Importing this platform moves that failure to where it can be fixed cheaply:
// a consumer whose catalog takes these constraints cannot compile against a
// HAPI this runtime will not serve.

val hapi = rootProject.extra["hapiVersion"] as String
val hl7Core = rootProject.extra["hl7CoreVersion"] as String

javaPlatform {
    // The dbo modules a consumer uses are here beside the third-party
    // constraints on purpose: one import, and the versions of both halves move
    // together, because they are the same decision.
    allowDependencies()
}

dependencies {
    constraints {
        // HAPI and the HL7 core are two families with two numbers, and this is
        // the file that says so out loud. `ca.uhn.fhir.*` is HAPI; the
        // `org.hl7.fhir.*` artifacts are the core it ships, pinned apart
        // because a FHIR ballot needs a core release that knows its code.
        api("ca.uhn.hapi.fhir:hapi-fhir-base:$hapi")
        api("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:$hapi")
        api("ca.uhn.hapi.fhir:hapi-fhir-structures-r5:$hapi")
        api("ca.uhn.hapi.fhir:hapi-fhir-validation:$hapi")
        api("ca.uhn.hapi.fhir:org.hl7.fhir.r4:$hl7Core")
        api("ca.uhn.hapi.fhir:org.hl7.fhir.r5:$hl7Core")
        api("ca.uhn.hapi.fhir:org.hl7.fhir.utilities:$hl7Core")
        api("ca.uhn.hapi.fhir:org.hl7.fhir.convertors:$hl7Core")
        api("ca.uhn.hapi.fhir:org.hl7.fhir.validation:$hl7Core")

        // dbo's own libraries, so a consumer pins one version of the store and
        // gets a consistent set rather than a mix nobody assembled.
        api("cloud.jengu.dbo:dbo-core:${project.version}")
        api("cloud.jengu.dbo:dbo-fhir-common:${project.version}")
        api("cloud.jengu.dbo:dbo-fhir-stack:${project.version}")
        api("cloud.jengu.dbo:dbo-fhir-r4:${project.version}")
        api("cloud.jengu.dbo:dbo-fhir-r5:${project.version}")
        api("cloud.jengu.dbo:dbo-fhir-element:${project.version}")
        api("cloud.jengu.dbo:dbo-work:${project.version}")
    }
}
