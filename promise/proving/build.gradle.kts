// Assertions that name the promise they prove.
//
// A test says what it proves with its product's @Cites annotation, and the
// catalogue reads that: it is how a promise comes to read PROVEN and by which
// test. What the annotation cannot say is WHICH assertion proved it, so a
// failure reports "expected true but was false" about a promise nobody named.
//
// Beside the framework rather than inside it, because of the one dependency it
// adds: the framework has none on purpose and is an adopter's compile-time
// dependency, while this is JUnit and belongs only on a test classpath. It
// knows no product's catalogue — @Cites is how it reads any of them — so
// nothing here names the store this repository happens to build.
plugins {
    id("java-library")
}

dependencies {
    api(project(":promise"))
    api("org.junit.jupiter:junit-jupiter-api:6.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
