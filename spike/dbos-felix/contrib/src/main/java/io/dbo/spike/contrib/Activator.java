package io.dbo.spike.contrib;

import io.dbo.spike.api.SpikeWorkflowsFactory;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    @Override
    public void start(BundleContext ctx) {
        SpikeWorkflowsFactory factory = SpikeWorkflowsImpl::new;
        ctx.registerService(SpikeWorkflowsFactory.class, factory, null);
    }

    @Override
    public void stop(BundleContext ctx) {
        // registration is cleaned up by the framework
    }
}
