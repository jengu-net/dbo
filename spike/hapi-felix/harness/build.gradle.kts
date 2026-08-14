dependencies {
    testImplementation(project(":api"))
    testImplementation("org.apache.felix:org.apache.felix.framework:7.0.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    dependsOn(":personality-r4:jar", ":personality-r5:jar")
    systemProperty(
        "spike.r4.jar",
        project(":personality-r4").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "spike.r5.jar",
        project(":personality-r5").tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
