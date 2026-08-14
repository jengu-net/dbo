// Provisioning operator (dbo#18, Slice B): runs as its own process/pod —
// a plain jar, NOT an OSGi bundle. Watches TenantRegistration CRs and
// executes the Hetzner pattern (role + database + Secret + ConfigMap entry)
// with a scoped provisioner role, never superuser. Also ships the in-cluster
// side: KubernetesSecretProvisioner (the dbo#17 seam backed by tenant
// Secrets) and SpecDirSync (ConfigMap -> the manager's spec directory).

dependencies {
    api(project(":core:dbo-tenant"))
    api("io.fabric8:kubernetes-client:7.3.1")
    // dbo-tenant embeds Hikari privately (bundle pattern); as a plain jar we
    // depend on it directly
    implementation("com.zaxxer:HikariCP:7.1.0")
}
