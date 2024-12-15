plugins {
    id("io.micronaut.library")
}

dependencies {
    testImplementation(mn.micronaut.test.junit5)
    testRuntimeOnly(mn.junit.jupiter.engine)
}
