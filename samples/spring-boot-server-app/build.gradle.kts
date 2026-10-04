// The clinic's own application: it serves DBO tenants, by adding one
// dependency, and performs the clinic's steps beside them.
//
// The assembly beside it (`assembly/spring-boot-server`) proves the wrapper
// works; this proves an APPLICATION can be built on it, which is a different
// claim and the one an integrator is actually asking about. The guide is its
// story, quoted from its source.
//
// The user stories are this application's tests. They boot it as any Spring
// Boot application is tested, with the worker embedded, and walk the sample
// world through its own classes.
//
// Not published. A sample is something to read and run, not something to
// depend on — the same reason the harness is not published.
plugins {
    id("java")
    // So a reader can run it: `./gradlew <this>:run`. The Spring Boot Gradle
    // plugin is not used here — it adds bootJar and bootRun, and what a sample
    // needs is a main class somebody can start, not a repackaged archive.
    id("application")
}

application {
    mainClass.set("cloud.jengu.dbo.samples.server.ServerApplication")
}

val springBootVersion = rootProject.extra["dboSpringBootVersion"] as String

dependencies {
    // THE ONE DEPENDENCY. Everything the container needs rides with it, and
    // nothing in this module names a Bundle, a BundleContext or a framework.
    implementation(project(":assembly:spring-boot-server"))

    // The application's own half: an ordinary Spring Boot web application,
    // which is what makes the point — the port, the filter chain and the
    // beans are the application's, and the store answers inside them.
    implementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")

    // The clinic's steps, performed in this JVM beside the store. The worker
    // application is also a thing that runs on its own; here it is a library
    // whose step beans arrive through its auto-configuration, and the lane
    // they are offered work over is this application's own port.
    implementation(project(":samples:spring-boot-worker-app"))
    // And the worker assembly's own vocabulary — its lanes, its initiator —
    // which the worker application depends on without passing on. The clinic
    // names it itself, because asking for work is the clinic's act.
    implementation(project(":assembly:spring-boot-worker"))
    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    // How an application on these assemblies is tested: a database for this
    // JVM, the world, a key, and a tenant that came up — all derived from
    // what `dbo.test.*` says this test needs.
    testImplementation(project(":assembly:spring-boot-test"))
    // An assertion that names its promise, and the catalogue the name is
    // typed to. The assertion knows no catalogue, so a test citing this
    // store's promises says so itself.
    testImplementation(project(":promise:proving"))
    testImplementation(project(":core:dbo-promises"))
    // The user stories cite the promises they prove, and the catalogue reads
    // those citations from the index this processor writes beside the classes.
    testAnnotationProcessor(project(":promise"))
    // The operator's side of the fleet story: one process outside every
    // container, reading what the deployment says about itself.
    testImplementation(project(":core:dbo-fleet"))
    // ApplicationContextRunner hands its callback an AssertJ-shaped context.
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The clinic's own bundle, carried as a resource rather than as classes: it is
// installed into the framework the application owns (OwningTheFramework), and
// its classes belong to that framework's class space, not to the application's.
val ownBundles: Configuration = configurations.create("ownBundles")
dependencies {
    ownBundles(project(":samples:ward-bundle")) { isTransitive = false }
}
tasks.named<ProcessResources>("processResources") {
    from(ownBundles) {
        into("bundles")
        rename { "ward-bundle.jar" }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Faces are cut once and brought up from, as the sample world's own
    // compose file has its node do: the first tenant on a version cuts it and
    // every tenant after loads it rather than expanding the version again,
    // half a minute or more each. Kept as long as the world's database is,
    // which is this test JVM — the compose file holds them on a tmpfs for the
    // same reason — so every run cuts its own, and a run whose deployment
    // could not cut one is one the stories can tell.
    val images = layout.buildDirectory.dir("face-images-$name").get().asFile
    systemProperty("dbo.face.images", images.absolutePath)
    doFirst { images.deleteRecursively() }
}

tasks.test {
    useJUnitPlatform {
        // The stories run in a JVM of their own, below: they share one context,
        // and a class here that builds a context of its own would be a second
        // world over the same database.
        excludeTags("story")
    }
    // A tenant comes up inside this test, which expands a FHIR version out of
    // the specification. Stated here rather than inherited, per the rule that
    // a test task loading the validator says its own number.
    maxHeapSize = "3g"
}

// The user stories, walked on the sample world: one context, one world, every
// story at once.
//
// Classes run concurrently and a class's legs run in order on one thread,
// because each leg is set up by the one before it. A failure that appears only
// when the stories run together is a defect in the store, so nothing here
// serialises them.
val storyTest = tasks.register<Test>("storyTest") {
    description = "Walks the user stories on the sample world, all at once."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("story")
    }
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    // Every face the world declares is expanded in this one JVM.
    maxHeapSize = "4g"
}

tasks.named("check") {
    dependsOn(storyTest)
}

tasks.named<JavaExec>("run") {
    // Stated rather than inherited: this application's configuration names the
    // world as ../sample-world, which is only that directory when the working
    // directory is this module. A run from elsewhere would come up serving
    // nobody and answering 404 to everything, which reads like a wrong URL.
    workingDir = projectDir
}
