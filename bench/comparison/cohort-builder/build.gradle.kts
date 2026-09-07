plugins { application }
repositories { mavenCentral() }
dependencies {
    implementation("ca.uhn.hapi.fhir:org.hl7.fhir.convertors:6.3.11")
    implementation("ca.uhn.hapi.fhir:org.hl7.fhir.r4:6.3.11")
    implementation("ca.uhn.hapi.fhir:org.hl7.fhir.r5:6.3.11")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.google.code.gson:gson:2.11.0")
}
application { mainClass.set("bench.ProviderExport") }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaExec> { maxHeapSize = "3g" }
