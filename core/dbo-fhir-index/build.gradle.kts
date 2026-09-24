plugins {
    id("biz.aQute.bnd.builder")
}

// The definition index: a version's elements as flat arrays over one interned
// dictionary, projected from the rows dbo-definitions writes.
//
// NOTHING. Not the toolchain, which is the whole point — a process holding
// this holds no worker context — and not the JDBC driver either: the rows are
// read through java.sql against whatever DataSource the caller has, so bnd
// computes an Import-Package of nothing but the JDK.

tasks.jar {
    bundle {
        bnd(mapOf(
            "Bundle-SymbolicName" to "cloud.jengu.dbo.fhir.index",
            "Export-Package" to "cloud.jengu.dbo.fhir.index;version=0.1.0",
        ))
    }
}
