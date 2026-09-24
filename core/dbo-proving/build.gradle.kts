// Assertions that name the promise they prove.
//
// A test says what it proves with @Proving, and the catalogue reads that: it
// is how a promise comes to read PROVEN and by which test. What the annotation
// cannot say is WHICH assertion proved it, so a failure reports "expected true
// but was false" about a promise nobody named.
//
// Its own module because of who needs it and who must not. The harness proves
// most of this store's promises and the Spring assemblies prove the rest, so
// neither can own it; and dbo-promises must not, because production bundles
// cite the catalogue and would then compile against JUnit.
plugins {
    id("java-library")
}

dependencies {
    // The catalogue's own annotation, which is what an assertion is checked
    // against. Not the promise framework alone: @Proving is typed to this
    // store's constants, so a guard that read it has to know them.
    api(project(":core:dbo-promises"))
    api("org.junit.jupiter:junit-jupiter-api:6.0.0")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
