// The published identity of the project. `dbo.repo.url` is a property so a
// repository move is one flag, not a sweep through every POM.
val projectUrl = (findProperty("dbo.repo.url") as String?) ?: "https://github.com/jengu-net/dbo"

// One-line summaries per module, used as the POM description. Maven Central
// rejects a bundle whose POM has no description, and "dbo-postgres" is not a
// description.
val moduleBlurbs = mapOf(
    "dbo-core" to "Zero-dependency object API: stored objects, identifiers, envelopes, declared handling.",
    "dbo-postgres" to "The Postgres engine: single-transaction writes, envelope indexing, history and the transactional outbox.",
    "dbo-fhir-common" to "Shared FHIR personality machinery, independent of FHIR version.",
    "dbo-fhir-r4" to "FHIR R4 personality with a private HAPI stack.",
    "dbo-fhir-r5" to "FHIR R5 personality with a private HAPI stack.",
    "dbo-rest" to "The FHIR HTTP surface: JDK HttpServer on virtual threads, no framework.",
    "dbo-auth" to "Per-tenant OIDC authority: JWS, JWKS, SMART scopes, role grants from the organisational model.",
    "dbo-pdi" to "Personal-data isolation: identifying elements encrypted in the payload, erasure by key destruction.",
    "dbo-policy" to "Tenant policies: append-only audit, write discipline and declarative retention.",
    "dbo-subscriptions" to "Durable FHIR subscription delivery over the change feed.",
    "dbo-sync" to "Declared content dependencies streamed between tenant stores.",
    "dbo-terminology" to "Concept-per-row terminology with \$expand, \$lookup and \$validate-code.",
    "dbo-maintenance" to "Sealed, attested archives: backup, restore, portable export and import.",
    "dbo-tenant" to "Tenant runtime wiring: spec files to live per-tenant service sets.",
    "dbo-tenant-k8s" to "In-cluster provisioning seam backed by operator-written Kubernetes Secrets.",
    "dbo-operator" to "Kubernetes operator reconciling TenantRegistration resources.",
    "dbo-test-model" to "A non-FHIR model used to prove the engine holds no FHIR knowledge.",
)

subprojects {
    apply(plugin = "java-library")
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    group = "cloud.jengu.dbo"
    // Snapshots on main; a release build passes -Pdbo.version=X.Y.Z (the
    // CI derives it from the v-tag) — fixed numbering for bundles AND images.
    version = (findProperty("dbo.version") as String?) ?: "0.1.0-SNAPSHOT"

    // Publish every library module. Named exclusions rather than a guess:
    // the distribution is an image, and the harness, the conformance report
    // and the bench are tools this repository runs on itself. Publishing any
    // of them would put a test rig on Maven Central under a name that
    // promises a library.
    val notALibrary = setOf(
        ":core:harness", ":core:dbo-server", ":core:conformance", ":bench:runner")
    if (project.path !in notALibrary) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        // Central requires a sources jar and a javadoc jar beside every
        // artifact. They are cheap here and useful to a consumer reading an
        // API that is deliberately small.
        the<JavaPluginExtension>().withSourcesJar()
        the<JavaPluginExtension>().withJavadocJar()

        configure<PublishingExtension> {
            repositories {
                // The project's own repository — PUBLIC, which is the whole
                // difference from the arrangement this replaced. Artifacts
                // here are ~320MB a release, comfortably past what Maven
                // Central's size thresholds admit (see RELEASING.md), and the
                // fat OSGi bundles are what embedded-Felix consumers actually
                // resolve. Central becomes viable once the packaging fronts
                // on the status page land; the coordinates and the signing
                // are already the same, so that is a destination change.
                maven {
                    name = "JenguRepo"
                    val snapshot = version.toString().endsWith("SNAPSHOT")
                    url = uri(
                        if (snapshot) "https://repo.jengu.cloud/repository/maven-snapshots/"
                        else "https://repo.jengu.cloud/repository/maven-releases/"
                    )
                    credentials {
                        username = System.getenv("NEXUS_USERNAME") ?: findProperty("jengu.repo.user") as String? ?: ""
                        password = System.getenv("NEXUS_PASSWORD") ?: findProperty("jengu.repo.key") as String? ?: ""
                    }
                }
                // Maven Central is not published to directly: the Central
                // Portal takes ONE bundle zip for the whole release. Every
                // module stages into a shared local repository laid out the
                // Maven way, and :centralBundle zips it. Kept warm for when
                // the artifacts are small enough to go there.
                maven {
                    name = "CentralStaging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
                }
            }
            publications {
                register<MavenPublication>("maven") {
                    from(components["java"])
                    groupId = "cloud.jengu.dbo"
                    artifactId = project.name

                    pom {
                        name.set(project.name)
                        description.set(
                            moduleBlurbs[project.name]
                                ?: "A module of DBO, a multi-tenant FHIR object store."
                        )
                        url.set(projectUrl)
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("$projectUrl/blob/main/LICENSE")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("jengu")
                                name.set("Jengu Net")
                                url.set(projectUrl)
                            }
                        }
                        scm {
                            url.set(projectUrl)
                            connection.set("scm:git:${projectUrl}.git")
                            developerConnection.set("scm:git:${projectUrl}.git")
                        }
                    }

                    // Fat bundles carry their private stacks INSIDE the jar
                    // (lib/ nested jars — HAPI, fabric8, DBOS). The POM must
                    // not redeclare them as transitive dependencies, or a
                    // Gradle consumer gets every class twice.
                    pom.withXml {
                        val embedded = configurations.findByName("embedded")
                            ?.dependencies?.map { it.name }?.toSet() ?: emptySet()
                        if (embedded.isNotEmpty()) {
                            val root = asNode()
                            @Suppress("UNCHECKED_CAST")
                            val depsNodes = (root.get("dependencies") as groovy.util.NodeList)
                            for (depsNode in depsNodes.filterIsInstance<groovy.util.Node>()) {
                                val toRemove = depsNode.children()
                                    .filterIsInstance<groovy.util.Node>()
                                    .filter { dep ->
                                        val artifact = (dep.get("artifactId") as groovy.util.NodeList)
                                            .filterIsInstance<groovy.util.Node>()
                                            .firstOrNull()?.text()
                                        artifact != null && embedded.any { artifact.startsWith(it) || it.startsWith(artifact) }
                                    }
                                toRemove.forEach { depsNode.remove(it) }
                            }
                        }
                    }
                }
            }
        }

        // Signing is REQUIRED by Central and absent everywhere else. Gated on
        // the key being present so an ordinary build, and every contributor's
        // build, needs no key at all.
        val signingKey = System.getenv("SIGNING_KEY")
        val signingPassword = System.getenv("SIGNING_PASSWORD")
        if (!signingKey.isNullOrBlank()) {
            configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(the<PublishingExtension>().publications["maven"])
            }
        }
    }
}

// The Central Portal accepts one zip per deployment, laid out as a Maven
// repository. Staging is shared across modules, so this zips the lot.
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zips the staged artifacts into a Maven Central Portal bundle."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("publishMavenPublicationToCentralStagingRepository") })
    from(layout.buildDirectory.dir("staging-deploy"))
    destinationDirectory.set(layout.buildDirectory.dir("central"))
    archiveFileName.set("dbo-central-bundle.zip")
    // Central rejects a bundle carrying maven-metadata files.
    exclude("**/maven-metadata*")
}
