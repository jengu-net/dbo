package io.dbo.spike.r5;

import io.dbo.spike.fhir.api.FhirPersonality;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import java.util.Hashtable;

public class Activator implements BundleActivator {

    @Override
    public void start(BundleContext ctx) {
        Hashtable<String, Object> props = new Hashtable<>();
        props.put("fhir.version", "R5");
        ctx.registerService(FhirPersonality.class, new R5Personality(), props);
    }

    @Override
    public void stop(BundleContext ctx) {
    }
}
