plugins {
    id("biz.aQute.bnd.builder")
}

// The checks over the definition index. The third answerer: the toolchain
// reads an object graph, the database reads the expanded rows, this reads the
// same rows as arrays in its own heap.
//
// The index, and the vocabulary a finding is carried in. Nothing else — no
// toolchain, no driver, and no JSON library either: a document is read by a
// scanner of forty lines here rather than by embedding a second private copy
// of one, because the property this module exists to have is that a node
// holding it carries nothing.

dependencies {
    api(project(":core:dbo-fhir-index"))
    // Finding: what a refusal is carried in, whichever answerer found it.
    api(project(":core:dbo-fhir-common"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.validate",
            "Export-Package" to "cloud.jengu.dbo.fhir.validate;version=0.1.0",
        ))
    }
}
