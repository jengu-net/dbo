subprojects {
    apply(plugin = "java-library")
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    group = "cloud.jengu.dbo"
    // Snapshots on main; a release build passes -Pdbo.version=X.Y.Z (the
    // CI derives it from the v-tag) — fixed numbering for bundles AND images.
    version = (findProperty("dbo.version") as String?) ?: "0.1.0-SNAPSHOT"

    // Publish every library module to the self-hosted repository (the same
    // storage the platform libraries use — anonymous reads, no quota
    // accountant). Consumers: the platform embeds dbo conditionally in
    // local-dev, the edge runtimes permanently. The dist (dbo-server) and
    // the harness are not libraries.
    if (project.path != ":core:harness" && project.path != ":core:dbo-server") {
        apply(plugin = "maven-publish")

        configure<PublishingExtension> {
            repositories {
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
            }
            publications {
                register<MavenPublication>("maven") {
                    from(components["java"])
                    groupId = "cloud.jengu.dbo"
                    artifactId = project.name
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
    }
}
