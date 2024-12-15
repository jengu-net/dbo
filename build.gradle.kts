import org.gradle.plugins.ide.idea.model.IdeaModel

plugins {
    id("org.antora").version("1.0.0")
}

antora {
    packages.put("asciidoctor-kroki", "latest")
    packages.put("@djencks/asciidoctor-glossary", "latest")
    packages.put("@djencks/asciidoctor-jsonpath", "latest")
}

subprojects {

    afterEvaluate { // Ensure plugins are applied before checking
        if (plugins.hasPlugin("java-library") or plugins.hasPlugin("application")) {

            // Apply configurations specific to java-library projects
            println("Applying common configuration to Java Library project: $name")

            repositories {
                mavenCentral()
            }

            apply(plugin = "eclipse")
            apply(plugin = "idea")
            apply(plugin = "checkstyle")
            apply(plugin = "jvm-test-suite")

            dependencies {
                // Utils
                add("implementation", mn.slf4j.api)
                add("annotationProcessor", mn.lombok)
                add("compileOnly", mn.lombok)
                add("testAnnotationProcessor", mn.lombok)
                add("testCompileOnly", mn.lombok)
                add("testImplementation", mn.junit.jupiter.api)
//                add("testRuntimeOnly", mn.junit.platform.suite)
                add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
                add("testRuntimeOnly", mn.junit.jupiter.engine)
                add("testRuntimeOnly", mn.logback.classic)
                add("runtimeOnly", mn.logback.classic)
            }

            extensions.configure<IdeaModel> {
                module {
                    isDownloadJavadoc = false
                    isDownloadSources = true
                }
            }

            extensions.configure<CheckstyleExtension> {
                toolVersion = "10.3.3"
                maxWarnings = 10000
                isIgnoreFailures = false
                configProperties = mapOf(
                    "org.checkstyle.google.suppressionfilter.config" to project(":").file("config/checkstyle/suppressions.xml")
                )
            }

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.toVersion("21")
                targetCompatibility = JavaVersion.toVersion("21")
            }

            tasks.withType<JavaCompile> {
                options.encoding = "UTF-8"
                options.isIncremental = true
            }

            tasks.withType<Jar> {
                manifest {
                    attributes["Implementation-Title"] = project.name
                    attributes["Implementation-Version"] = project.version
                }
            }

            tasks.withType(Test::class.java) {
                // Use the built-in JUnit support of Gradle.
                useJUnitPlatform()
            }

        }
    }
}

// Disable `antora` for all subprojects
gradle.projectsEvaluated {
    if (gradle.startParameter.projectDir == rootProject.projectDir) {
        rootProject.tasks.named("antora").configure {
            enabled = true
        }
    } else {
        subprojects {
            tasks.findByName("antora")?.let {
                it.enabled = false
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
