// Requirements as code (§16): promises declared once, cited everywhere,
// composed across products. ZERO runtime dependencies on purpose — this
// module is an API, a small annotation processor and a registry, and any
// dependency here becomes every adopter's dependency.

plugins {
    id("biz.aQute.bnd.builder")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.promise",
            "Export-Package" to "cloud.jengu.dbo.promise;version=0.1.0",
        ))
    }
}
