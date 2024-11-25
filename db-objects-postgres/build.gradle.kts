plugins {
    `java-library`
    eclipse
    idea
    alias(dbo.plugins.owasp.dependencycheck)
}

dependencies {

    // Utils
    api(mn.slf4j.api)
    annotationProcessor(mn.lombok)
    compileOnly(mn.lombok)
    testAnnotationProcessor(mn.lombok)
    testCompileOnly(mn.lombok)
    testImplementation(mn.junit.jupiter.engine)
    testRuntimeOnly(mn.logback.classic)

    api(project(":db-objects"))
    // vertx async postgres client driver
    api(mn.vertx.pg.client)
    runtimeOnly(dbo.netty.all)          // needed for vertx pq client
    runtimeOnly(mn.ongres.scram.client) // needed for vertx pq client

    // for liquibase
    implementation(mn.liquibase)
    implementation(dbo.liquibase.slf4j)
    implementation(dbo.liquibase.sessionlock)
    implementation(mn.postgresql) //  'org.postgresql:postgresql:42.5.4'
}

tasks.test {
    // Use the built-in JUnit support of Gradle.
    useJUnitPlatform()
}
