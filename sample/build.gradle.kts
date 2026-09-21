// The sample application, and the world it runs against.
//
// The guide is documentation about this module: a chapter includes the file
// compiled here, so a chapter cannot show a call that no longer exists, and a
// reader who comes to the code first finds it where code lives rather than
// inside the documentation tree.
//
// `world/` holds the tenant specs the guide's deployment serves. They are the
// sample's configuration — which tenants exist, which standard each speaks,
// what each declares — and the compose file beside the guide mounts them.
plugins {
    id("java")
}

dependencies {
    // What an integrator compiles against: the work vocabulary and the lane
    // that carries it. There is deliberately no store on this path.
    api(project(":core:dbo-runner"))
    api(project(":core:dbo-work"))
    implementation(project(":core:dbo-tenant"))
    // The chapters are documentation about this module, and one test here
    // holds them to it. It reads files and needs no world.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // What this test reads, said so the cache can tell whether the answer
    // still holds. It opens the chapters and the world by path rather than
    // through the classpath, and a cached pass over a chapter that has since
    // changed is exactly the failure a cache introduces to a build whose
    // tasks do not declare what they read.
    inputs.dir(rootProject.file("docs/guide")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("sample/world")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// Javadoc on example code would demand the ceremony the examples exist to be
// free of: these are read as prose in a chapter, not as an API.
tasks.javadoc { enabled = false }
