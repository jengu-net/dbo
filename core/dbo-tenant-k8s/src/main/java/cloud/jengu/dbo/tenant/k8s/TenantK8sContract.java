package cloud.jengu.dbo.tenant.k8s;

import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

/**
 * The k8s naming contract shared by the operator (which writes these
 * objects) and the serving side (which reads them). Lives here — the
 * lowest layer both depend on — so neither hardcodes the other's names.
 */
public final class TenantK8sContract {

    public static final ResourceDefinitionContext CRD_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("jengu.cloud")
            .withVersion("v1alpha1")
            .withKind("TenantRegistration")
            .withPlural("tenantregistrations")
            .withNamespaced(true)
            .build();

    /** The ConfigMap whose keys are tenant spec files (<code>.json). */
    public static final String CONFIGMAP = "dbo-tenants";

    public static final String TENANT_LABEL = "jengu.cloud/tenant";

    private TenantK8sContract() {
    }

    public static String secretName(String code) {
        return "tenant-" + code + "-db";
    }
}
