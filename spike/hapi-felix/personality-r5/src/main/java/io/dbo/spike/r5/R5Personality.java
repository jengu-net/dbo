package io.dbo.spike.r5;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.parser.IParser;
import io.dbo.spike.fhir.api.FhirPersonality;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import java.util.List;

public class R5Personality implements FhirPersonality {

    private volatile FhirContext ctx;
    private volatile long initMillis = -1;

    private synchronized FhirContext ctx() {
        if (ctx == null) {
            long t0 = System.nanoTime();
            ctx = FhirContext.forR5();
            ctx.getResourceDefinition("Patient");
            initMillis = (System.nanoTime() - t0) / 1_000_000;
        }
        return ctx;
    }


    /** Landmine #2 fix: HAPI's cache/service discovery uses the thread
     * context classloader; inside a bundle that is the host's loader, which
     * cannot see the embedded jars. Every entry point runs under the bundle
     * classloader as TCCL. */
    private <T> T withTccl(java.util.function.Supplier<T> body) {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(getClass().getClassLoader());
        try {
            return body.get();
        } finally {
            t.setContextClassLoader(old);
        }
    }

    @Override
    public String classOrigin(String fqcn) {
        try {
            Class<?> c = Class.forName(fqcn, false, getClass().getClassLoader());
            return String.valueOf(c.getClassLoader());
        } catch (ClassNotFoundException e) {
            return "absent";
        }
    }

    @Override
    public String fhirVersion() {
        return "R5";
    }

    @Override
    public String reserialize(String resourceJson) {
        return withTccl(() -> {
            IParser p = ctx().newJsonParser();
            IBaseResource r = p.parseResource(resourceJson);
            return p.encodeResourceToString(r);
        });
    }

    @Override
    public List<String> evalPath(String resourceJson, String fhirPath) {
        return withTccl(() -> {
            IBaseResource r = ctx().newJsonParser().parseResource(resourceJson);
            IFhirPath fp = ctx().newFhirPath();
            return fp.evaluate(r, fhirPath, IPrimitiveType.class).stream()
                    .map(IPrimitiveType::getValueAsString)
                    .toList();
        });
    }

    @Override
    public List<String> validate(String resourceJson) {
        return List.of("validation not bundled in the R5 spike personality");
    }

    @Override
    public boolean canLoad(String fqcn) {
        try {
            Class.forName(fqcn, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public long contextInitMillis() {
        ctx();
        return initMillis;
    }
}
