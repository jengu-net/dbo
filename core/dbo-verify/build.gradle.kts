plugins {
    id("java")
}

description = "Standalone archive verifier: an archive, two public keys, an answer."

dependencies {
    implementation(project(":core:dbo-maintenance"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * One file somebody can be handed. The verifier has to run on a laptop that
 * has never met this project — an auditor's, a regulator's — so "download
 * these jars and set a classpath" is not an answer.
 */
tasks.register<Jar>("verifierJar") {
    archiveBaseName.set("dbo-verify")
    manifest { attributes("Main-Class" to "cloud.jengu.dbo.verify.Verify") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            // The verifier reads a file and checks signatures. A database
            // driver on its classpath would be weight it never touches, and
            // one more thing an auditor has to be told to ignore.
            .filter { it.name.endsWith("jar") && !it.name.startsWith("postgresql") }
            .map { zipTree(it) }
    })
}
