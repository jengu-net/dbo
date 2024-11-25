plugins {
    `java-library`
    alias(dbo.plugins.owasp.dependencycheck)
    checkstyle
}

dependencies {

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

