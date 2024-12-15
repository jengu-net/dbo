plugins {
    id("io.micronaut.application")
}

dependencies {
    // Micronaut mandatory libs
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.serde.jackson)
    runtimeOnly(mn.snakeyaml)

    // Web Application
    //annotationProcessor(mn.micronaut.http.validation)
    //implementation(mn.micronaut.views.thymeleaf)
    //implementation(mn.micronaut.views.htmx)
    //runtimeOnly("org.webjars.npm:htmx.org:2.0.3")
}

application {
    mainClass.set("io.dbo.demo.micronaut.petclinic.PetclinicApplication")
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.dbo.*")
    }
}


