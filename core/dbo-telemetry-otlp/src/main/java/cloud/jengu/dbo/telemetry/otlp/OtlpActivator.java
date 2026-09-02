package cloud.jengu.dbo.telemetry.otlp;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Hands the framework's properties to the exporter, which is constructed by
 * the seam's ServiceLoader lookup rather than by this activator, so it has
 * no context of its own to read a deployment's configuration from.
 */
public final class OtlpActivator implements BundleActivator {

    @Override
    public void start(BundleContext context) {
        OtlpTelemetry.configuredBy(context::getProperty);
    }

    @Override
    public void stop(BundleContext context) {
        OtlpTelemetry.configuredBy(null);
        OtlpTelemetry.closeAll();
    }
}
