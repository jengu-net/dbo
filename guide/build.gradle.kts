import java.time.Duration

// The guide, executed.
//
// Its own module rather than a source set inside the harness, because what it
// needs is different from everything else: a built image and a running world,
// not a database and a classpath. Keeping it separate is also what lets it be
// run on its own — a chapter's commands are the thing a reader copies, and
// waiting thirty-eight minutes to find out one of them rotted is how a check
// stops being run.
//
// It runs in CI as its own job, beside the tree-built shell run rather than
// instead of it, and it is where promises move as they come out of tests that
// each build a world of their own. Its citation index is read by the promise
// projection in `:core:harness`, which is why that module depends on this
// one's test output.

plugins {
    id("java")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // @Proving citations, for when promises start moving here.
    testImplementation(project(":core:dbo-promises"))
    testAnnotationProcessor(project(":promise"))
}

tasks.test {
    useJUnitPlatform()
    // The world is a container set, so there is nothing to parallelise within
    // a class and everything to lose by racing two of them for port 8090.
    maxParallelForks = 1
    // A cold world provisions six databases with a terminology baseline each.
    timeout.set(Duration.ofMinutes(30))
    testLogging { events("passed", "failed") }
}
