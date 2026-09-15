// A driver bundle, and nothing else.
//
// It exists to prove one sentence that had no proof: that a bundle
// registering a StepService is picked up and driven, with nothing wired in a
// composition root. The runner's own build says the gap plainly — its
// activator is "the whiteboard the runtime's own bundles never fill: with no
// StepService and no Lane registered it cycles over nothing" — and every test
// that stood behind the embeddable promise constructed the runner itself,
// which proves the seam and not the wiring.
//
// So this is the missing half: a bundle that contributes a step the way a
// device driver will, installed into a real framework beside the runner, with
// no test reaching inside either.
//
// NOT in the runtime bundle set. Nothing in the distribution imports it and
// nothing should — a probe that shipped would be a step service registered on
// every deployment, which is the opposite of what it is for.

plugins {
    id("biz.aQute.bnd.builder")
}

dependencies {
    // The seam under test, and nothing more. What this bundle can reach is
    // itself part of the claim: a driver needing the store, a transport or an
    // orchestrator would say the promise is narrower than it reads.
    implementation(project(":core:dbo-runner"))
    compileOnly("org.osgi:osgi.core:8.0.0")
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.probe",
            "Bundle-Activator" to "cloud.jengu.dbo.probe.ProbeActivator",
        ))
    }
}
