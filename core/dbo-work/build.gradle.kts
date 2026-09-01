plugins {
    id("biz.aQute.bnd.builder")
}

// Run records: what a step did, who holds it now, and what is left.
// Engine-side and domain-free — a run is stored, never rendered here, because
// rendering it as a domain's work resource is a face's translation.
//
// Its own JSON, like every other module that authors payloads: a private
// twenty-line reader is cheaper than a bundle dependency shared for the sake
// of not repeating one.

dependencies {
    api(project(":core:dbo-core"))
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.work",
            "Export-Package" to "cloud.jengu.dbo.work;version=0.1.0",
        ))
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // @Proving citations only — dbo-promises depends on :promise alone, so
    // this does not create a cycle back into dbo-work.
    testImplementation(project(":core:dbo-promises"))
    testAnnotationProcessor(project(":promise"))
}

tasks.test { useJUnitPlatform() }
