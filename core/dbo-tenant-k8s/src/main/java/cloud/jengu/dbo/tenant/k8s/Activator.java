package cloud.jengu.dbo.tenant.k8s;

import cloud.jengu.dbo.tenant.TenantDatabaseProvisioner;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * In-cluster wiring (dbo#19): when {@code dbo.tenant.k8s.namespace} is set,
 * registers {@link KubernetesSecretProvisioner} as the mandatory
 * {@link TenantDatabaseProvisioner} service — the dbo-tenant manager tracks
 * it and serves. The serving pod's k8s identity needs only {@code secrets:
 * get}; it cannot touch TenantRegistrations, so erasure is unrepresentable
 * from the serving path.
 *
 * <p>Without the property this bundle is inert — the local default (or any
 * other provisioner) wins, unchanged from dbo#17.
 */
public final class Activator implements BundleActivator {

    private KubernetesClient client;
    private KubernetesSecretProvisioner provisioner;
    private ServiceRegistration<TenantDatabaseProvisioner> registration;

    @Override
    public void start(BundleContext ctx) {
        String namespace = ctx.getProperty("dbo.tenant.k8s.namespace");
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        client = new KubernetesClientBuilder().build(); // in-cluster config in a pod
        provisioner = new KubernetesSecretProvisioner(client, namespace);
        registration = ctx.registerService(TenantDatabaseProvisioner.class, provisioner, null);
    }

    @Override
    public void stop(BundleContext ctx) {
        if (registration != null) {
            registration.unregister();
        }
        if (provisioner != null) {
            provisioner.close();
        }
        if (client != null) {
            client.close();
        }
    }
}
