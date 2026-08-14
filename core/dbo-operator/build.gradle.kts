// Provisioning operator (dbo#18, Slice B): runs as its own process/pod —
// a plain jar, NOT an OSGi bundle. Watches TenantRegistration CRs and
// executes the Hetzner pattern (role + database + Secret + ConfigMap entry)
// with a scoped provisioner role, never superuser. Also ships the in-cluster
// side: KubernetesSecretProvisioner (the dbo#17 seam backed by tenant
// Secrets) and SpecDirSync (ConfigMap -> the manager's spec directory).

plugins {
    application
}

application {
    mainClass = "cloud.jengu.dbo.operator.Main"
}

// The operator process does plain JDBC + the k8s API. dbo-tenant's serving
// stack (personalities with private HAPI, REST, engine) reaches this
// module's runtime classpath transitively but is only exercised by
// TenantRuntimeManager — which runs in the SERVING process, never in the
// operator pod. Excluding it keeps the image ~30MB instead of ~500MB.
configurations.runtimeClasspath {
    exclude(group = "cloud.jengu.dbo", module = "dbo-fhir-r4")
    exclude(group = "cloud.jengu.dbo", module = "dbo-fhir-r5")
    exclude(group = "cloud.jengu.dbo", module = "dbo-rest")
    exclude(group = "cloud.jengu.dbo", module = "dbo-postgres")
}

dependencies {
    // the JDBC driver must be on the runtime classpath — the operator is a
    // standalone process, nothing else supplies it
    runtimeOnly("org.postgresql:postgresql:42.7.11")
    api(project(":core:dbo-tenant"))
    // the k8s naming contract + secret-backed provisioner live in the
    // tenant-k8s bundle module; as a plain jar we use its classes directly
    api(project(":core:dbo-tenant-k8s"))
    api("io.fabric8:kubernetes-client:7.3.1")
}
