import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    id("biz.aQute.bnd.builder")
}

// The element-model face and the definitions it serves from.
//
// A face is nothing without the definitions it validates and extracts
// against, and a store must not fetch them: no network call at bring-up, no
// writable cache outside its own state, and no build whose output depends on
// what a registry served that day. So they are pinned here, verified by
// digest, and embedded in the bundle
// (REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE).

val embedded: Configuration = configurations.create("embedded")

// The INDEX ships in this bundle and the packages it names ship in the fragment
// beside it (core/dbo-fhir-packages). Which versions this runtime serves was
// read off the tarballs on the classpath, so a node shipping none registered no
// face and served nobody — the runtime disappearing because its resources did.
// Announcing a version and being able to build a context for it are different
// facts, and only the second one weighs 68 MB.
val definitionsIndexFiles: Configuration = configurations.create("definitionsIndexFiles")
configurations.implementation.get().extendsFrom(embedded)

dependencies {
    add("definitionsIndexFiles", project(mapOf(
        "path" to ":core:dbo-fhir-packages",
        "configuration" to "definitionsIndexFiles")))
    api(project(":core:dbo-core"))
    api(project(":core:dbo-fhir-common"))
    // the HL7 engine: this bundle imports it, one exporter for the framework
    api(project(":core:dbo-fhir-stack"))
    // the native form validation consults for the tenant's own codes
    api(project(":core:dbo-terminology"))
    // the native form a definition is expanded into when it arrives
    api(project(":core:dbo-definitions"))
    // and the same rows as flat arrays, with the reader that runs a compiled
    // path over a document. The envelope built here uses it rather than a
    // second scanner of its own — implementation, because nothing in this
    // module's own surface mentions them and bnd computes the imports.
    implementation(project(":core:dbo-fhir-index"))
    implementation(project(":core:dbo-fhir-validate"))
    // Notifications are composed here because this is the face that serves
    // tenants: the R4 and R5 personalities carry their own composers, hung off
    // stores no request reaches. The same edge the personalities already have,
    // moved to where it does something.
    api(project(":core:dbo-subscriptions"))
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    compileOnly("org.osgi:osgi.core:8.0.0")
    // A token-level JSON copier, so a stored payload reaches a reader with the
    // ancestor slots replaced and nothing else touched. Embedded PRIVATELY:
    // the shared stack has its own copy inside and exports none of it, and a
    // second exporter of com.fasterxml.jackson.core would be a split package
    // over a library two bundles use for different things.
    embedded("com.fasterxml.jackson.core:jackson-core:2.22.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // this face's tests cite the store's promises; dbo-promises is a leaf
    // (it depends on the promise framework and nothing else), so citing from
    // here adds no cycle
    testImplementation(project(":core:dbo-promises"))
    // the promise framework's processor indexes @Proving citations at THIS
    // module's test-compile time; without this configuration the index is
    // silently absent
    testAnnotationProcessor(project(":promise"))
    // the engine logs; compileOnly above is the runtime's arrangement, and a
    // test has no bundle to import it from
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The definitions are NOT here any more: they are a fragment of this bundle
// (core/dbo-fhir-packages), attached where a face root runs and absent
// everywhere else. A node without it resolves and starts exactly as before and
// finds no definitions to load, which is the property item 025 was for.
tasks.named<ProcessResources>("processResources") {
    from(definitionsIndexFiles) { into("definitions") }
    // How a version is discovered where there is no service registry
    from("src/main/resources-services")
}

val stackJar = project(":core:dbo-fhir-stack").tasks.named<Jar>("jar")

/**
 * Every engine package the shared bundle exports, imported wholesale — the
 * same reasoning as the personalities: this face reaches HL7 types it never
 * names, and a missing entry resolves and then throws on first use.
 */
fun engineImports(): List<String> =
    JarFile(stackJar.get().archiveFile.get().asFile).use { jar ->
        jar.manifest.mainAttributes.getValue("Export-Package").split(",")
    }

tasks.jar {
    dependsOn(stackJar)
    // And it is an INPUT, not only an order: this manifest is computed FROM
    // the stack's exports, so a change there has to rebuild this jar. Ordered
    // but not declared, Gradle called this task up to date after the stack's
    // exports changed underneath it — and the bundle then asked the container
    // for a package nothing exports any more, which fails at bring-up in a
    // test nobody would connect to a build-cache decision.
    inputs.file(stackJar.get().archiveFile)
    into("META-INF") { from(rootProject.file("THIRD-PARTY.md")) }
    bundle {
        bnd(provider {
            val jars = embedded.resolve().sortedBy { it.name }
            listOf(
                "Bundle-SymbolicName: cloud.jengu.dbo.fhir.element",
                // The bundle announces every version it carries; see Activator.
                "Bundle-Activator: cloud.jengu.dbo.fhir.element.Activator",
                "Bundle-ClassPath: ." + jars.joinToString("") { ",lib/${it.name}" },
                "-includeresource: " + jars.joinToString(",") { "lib/${it.name}=${it.absolutePath}" },
                "Export-Package: cloud.jengu.dbo.fhir.element;version=0.1.0",
                "-noimportjava: true",
                // Two halves, computed two ways, and neither written by hand.
                //
                // The ENGINE half is named wholesale from the one bundle that
                // exports it: this face reaches HAPI types it never names, and
                // an import bytecode analysis did not see resolves and then
                // throws NoClassDefFoundError on first use.
                //
                // The DBO half is bnd's, matched by pattern, so a new
                // reference to a sibling bundle needs no edit here. This is
                // the bundle that had the longest hand-kept list in the repo,
                // and the one whose list went stale in the same commit that
                // added cloud.jengu.dbo.core.process.
                "Import-Package: " + (engineImports() + listOf(
                    "cloud.jengu.dbo.*",
                    "org.slf4j",
                    "org.osgi.*",
                    "!*",
                )).joinToString(","),
            ).joinToString("\n")
        })
    }
}

tasks.test {
    useJUnitPlatform()
    // A version's definitions are tens of megabytes of JSON parsed into a
    // context; the default worker heap is not enough to hold one.
    // Every carried version's definitions can be resident at once: the gate
    // holds all three to the same claim in one run.
    maxHeapSize = "4g"
}
