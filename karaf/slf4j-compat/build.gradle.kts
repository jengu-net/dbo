// A fragment of slf4j-api that re-exports its packages at 1.7 versions.
//
// pax-url-aether is the mvn: handler the console's whole loop reads through,
// and it is built against slf4j 1.7: it imports org.slf4j.spi;[1.7,2.0), which
// slf4j-api 2.0.18 exports at 2.0.18. Supplying the real 1.7 API beside it puts
// TWO class spaces in one bundle's wiring — pax-url took org.slf4j from 2.0.18
// and org.slf4j.impl from the 1.7 binding, and died with a loader constraint
// violation on ILoggerFactory.
//
// A fragment exports the HOST's own classes under a second version instead. One
// class space, both version ranges satisfied, and no second implementation to
// disagree with the first. No code — the manifest is the whole artifact.
plugins {
    id("biz.aQute.bnd.builder")
}

base { archivesName.set("dbo-slf4j-compat") }

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.karaf.slf4j.compat",
            "Fragment-Host" to "slf4j.api",
            "Export-Package" to
                "org.slf4j;version=1.7.36," +
                "org.slf4j.spi;version=1.7.36," +
                "org.slf4j.helpers;version=1.7.36," +
                "org.slf4j.event;version=1.7.36",
        ))
    }
}
