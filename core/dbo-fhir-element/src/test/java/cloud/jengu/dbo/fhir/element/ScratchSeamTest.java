package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.r5.terminologies.utilities.ValidationResult;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.utilities.validation.ValidationOptions;
import org.hl7.fhir.validation.ValidatorUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

class ScratchSeamTest {

    /** Core + tools fully; terminology packages: ValueSets only. */
    private static SimpleWorkerContext lean(String code) throws Exception {
        SimpleWorkerContext context = null;
        for (CarriedDefinitions.Carried c : CarriedDefinitions.forVersion(code)) {
            byte[] raw;
            try (InputStream in = CarriedDefinitions.class
                    .getResourceAsStream("/definitions/" + c.file())) {
                raw = in.readAllBytes();
            }
            NpmPackage npm = NpmPackage.fromPackage(new ByteArrayInputStream(raw));
            var loader = c.name().startsWith("hl7.terminology")
                    ? new org.hl7.fhir.convertors.loaders.loaderR5.R4ToR5Loader(
                            java.util.Set.of("ValueSet"),
                            new org.hl7.fhir.convertors.loaders.loaderR5
                                    .NullLoaderKnowledgeProviderR5(),
                            npm.fhirVersion())
                    : ValidatorUtils.loaderForVersion(npm.fhirVersion());
            if (context == null) {
                context = new SimpleWorkerContext.SimpleWorkerContextBuilder()
                        .withAllowLoadingDuplicates(true).fromPackage(npm, loader, true);
            } else {
                context.loadFromPackage(npm, loader);
            }
        }
        return context;
    }

    @Test
    void seams() throws Exception {
        long t = System.currentTimeMillis();
        SimpleWorkerContext shared = lean("r4");
        System.out.println("SEAM lean(valueset-only terminology) build: "
                + (System.currentTimeMillis() - t) + "ms");

        // E2: is the binding's ValueSet resolvable?
        ValueSet marital = shared.fetchResource(ValueSet.class,
                "http://hl7.org/fhir/ValueSet/marital-status");
        System.out.println("SEAM marital-status ValueSet present: " + (marital != null));

        // E1: copy-constructor cost
        t = System.currentTimeMillis();
        SimpleWorkerContext copy = new SimpleWorkerContext(shared);
        System.out.println("SEAM copy ctor: " + (System.currentTimeMillis() - t) + "ms");

        // E3: which context methods carry the membership question?
        class TenantContext extends SimpleWorkerContext {
            final List<String> asked = new ArrayList<>();
            TenantContext(SimpleWorkerContext other) throws Exception { super(other); }
            @Override
            public ValidationResult validateCode(ValidationOptions o, Coding c, ValueSet v) {
                asked.add("coding " + c.getSystem() + "|" + c.getCode());
                return super.validateCode(o, c, v);
            }
            @Override
            public ValidationResult validateCode(ValidationOptions o,
                    org.hl7.fhir.r5.model.CodeableConcept c, ValueSet v) {
                asked.add("cc " + c.getCodingFirstRep().getSystem());
                return super.validateCode(o, c, v);
            }
            @Override
            public ValidationResult validateCode(ValidationOptions o, String system,
                    String version, String code, String display) {
                asked.add("string " + system + "|" + code);
                return super.validateCode(o, system, version, code, display);
            }
            @Override
            public org.hl7.fhir.r5.model.CodeSystem fetchCodeSystem(String system) {
                if (system != null && system.contains("loinc")) {
                    asked.add("fetchCodeSystem " + system);
                }
                return super.fetchCodeSystem(system);
            }
            @Override
            public org.hl7.fhir.r5.context.IWorkerContext.SystemSupportInformation
                    getTxSupportInfo(String system, String version) {
                asked.add("txSupport " + system);
                return super.getTxSupportInfo(system, version);
            }
        }
        TenantContext tenant = new TenantContext(shared);
        var validator = new org.hl7.fhir.validation.instance.InstanceValidator(tenant,
                new ElementHostServices(tenant),
                org.hl7.fhir.r5.utils.xver.XVerExtensionManagerFactory.createExtensionManager(tenant),
                new org.hl7.fhir.r5.utils.validation.ValidatorSession(),
                new org.hl7.fhir.validation.ValidatorSettings());
        validator.setFetcher(new ElementFetcher(tenant));
        validator.setPolicyAdvisor(new ElementValidationPolicy());
        List<ValidationMessage> messages = new ArrayList<>();
        String obs = "{\"resourceType\":\"Observation\",\"status\":\"final\","
                + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\","
                + "\"code\":\"9999-9\"}]}}";
        validator.validate(null, messages, new ByteArrayInputStream(
                obs.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                org.hl7.fhir.r5.elementmodel.Manager.FhirFormat.JSON);
        System.out.println("SEAM asked: " + tenant.asked);
        for (ValidationMessage m : messages) {
            System.out.println("SEAM msg [" + m.getLevel() + "] " + m.getMessage());
        }
    }
}
