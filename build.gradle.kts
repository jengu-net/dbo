plugins {
    id("org.antora").version("1.0.0")
}

antora {
    packages.put("asciidoctor-kroki", "latest")
    packages.put("@djencks/asciidoctor-glossary", "latest")
    packages.put("@djencks/asciidoctor-jsonpath", "latest")
}

subprojects {
    repositories {
        mavenCentral()
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