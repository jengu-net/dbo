package io.dbo.spike.r4;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import io.dbo.spike.fhir.api.FhirPersonality;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import java.util.List;

public class R4Personality implements FhirPersonality {

    private volatile FhirContext ctx;
    private volatile FhirValidator validator;
    private volatile long initMillis = -1;

    private synchronized FhirContext ctx() {
        if (ctx == null) {
            long t0 = System.nanoTime();
            ctx = FhirContext.forR4();
            // force model scan so timing is honest
            ctx.getResourceDefinition("Patient");
            initMillis = (System.nanoTime() - t0) / 1_000_000;
        }
        return ctx;
    }

    private synchronized FhirValidator validator() {
        if (validator == null) {
            FhirContext c = ctx();
            var chain = new ValidationSupportChain(
                    new DefaultProfileValidationSupport(c),
                    new InMemoryTerminologyServerValidationSupport(c),
                    new CommonCodeSystemsTerminologyService(c));
            FhirValidator v = c.newValidator();
            v.registerValidatorModule(new FhirInstanceValidator(chain));
            validator = v;
        }
        return validator;
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
        return "R4";
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
        return withTccl(() -> {
            IBaseResource r = ctx().newJsonParser().parseResource(resourceJson);
            ValidationResult result = validator().validateWithResult(r);
            return result.getMessages().stream()
                    .map(m -> m.getSeverity() + " " + m.getLocationString() + ": " + m.getMessage())
                    .toList();
        });
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
