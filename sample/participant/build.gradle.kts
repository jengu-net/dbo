// The external participant: a second process that joins somebody else's
// process with a capability of its own.
//
// It is smaller than the sample beside it on purpose. What it depends on is
// the lane and the work vocabulary — no store, no tenant, no face — because
// that is the whole of what a party outside the deployment compiles against,
// and a dependency here that the sample does not have would be a claim that
// joining costs more than it does.
plugins {
    id("java")
}

dependencies {
    api(project(":core:dbo-runner"))
    api(project(":core:dbo-work"))
}

tasks.javadoc { enabled = false }
