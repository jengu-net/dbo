package io.dbo.spike.harness;

import io.dbo.spike.fhir.api.FhirPersonality;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike: HAPI stacks as private personality bundles (arc42-009 §7.3).
 *
 * R4 and R5 personalities coexist in one Felix container, each with a full
 * private HAPI copy. HAPI types never cross the api boundary.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HapiFelixSpikeTest {

    static Framework framework;

    static final String R4_PATIENT = """
            {"resourceType":"Patient","id":"p1",
             "identifier":[{"system":"https://ee.ee/eid","value":"39001010000"}],
             "name":[{"family":"Kask","given":["Jaan"]}],
             "gender":"male","birthDate":"1990-01-01"}""";

    // R5-only twist: Patient.contact uses new R5 fields elsewhere; simplest
    // R5-only construct on Patient itself: same shape parses in both — so we
    // use an R5-only resource type below for the divergence probe instead.
    static final String R5_SUBSCRIPTION_TOPIC = """
            {"resourceType":"SubscriptionTopic","id":"st1",
             "url":"https://dbo.dev/topics/obs","status":"active"}""";

    @BeforeAll
    void up() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage",
                Files.createTempDirectory("dbo-hapi-felix").toString());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        config.put("org.osgi.framework.system.packages.extra",
                "io.dbo.spike.fhir.api;version=\"1.0.0\"");

        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class)
                .findFirst().orElseThrow();
        framework = factory.newFramework(config);
        framework.start();

        BundleContext ctx = framework.getBundleContext();
        for (String prop : List.of("spike.r4.jar", "spike.r5.jar")) {
            Bundle b = ctx.installBundle("file:" + System.getProperty(prop));
            b.start();
        }
    }

    @AfterAll
    void down() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20_000);
        }
    }

    private FhirPersonality personality(String version) throws Exception {
        BundleContext ctx = framework.getBundleContext();
        var refs = ctx.getServiceReferences(FhirPersonality.class,
                "(fhir.version=" + version + ")");
        return ctx.getService(refs.iterator().next());
    }

    @Test
    @Timeout(120)
    void scenarioA_r4ParseAndFhirPath() throws Exception {
        FhirPersonality r4 = personality("R4");
        String out = r4.reserialize(R4_PATIENT);
        assertTrue(out.contains("\"Kask\""));
        assertEquals(List.of("Jaan"), r4.evalPath(R4_PATIENT, "Patient.name.given"));
        assertEquals(List.of("39001010000"),
                r4.evalPath(R4_PATIENT,
                        "Patient.identifier.where(system='https://ee.ee/eid').value"));
    }

    @Test
    @Timeout(120)
    void scenarioB_r5CoexistsAndDiverges() throws Exception {
        FhirPersonality r5 = personality("R5");
        String out = r5.reserialize(R4_PATIENT); // same shape is valid R5
        assertTrue(out.contains("\"Kask\""));
        assertEquals(List.of("Jaan"), r5.evalPath(R4_PATIENT, "Patient.name.given"));

        // divergence probe: SubscriptionTopic exists in R5, not in R4
        String topic = r5.reserialize(R5_SUBSCRIPTION_TOPIC);
        assertTrue(topic.contains("SubscriptionTopic"));
        boolean r4Parses;
        try {
            personality("R4").reserialize(R5_SUBSCRIPTION_TOPIC);
            r4Parses = true;
        } catch (RuntimeException e) {
            r4Parses = false;
        }
        assertFalse(r4Parses, "R4 personality parsed an R5-only resource type");
    }

    @Test
    @Timeout(300)
    void scenarioC_r4ValidationWithProfiles() throws Exception {
        FhirPersonality r4 = personality("R4");
        List<String> cleanIssues = r4.validate(R4_PATIENT).stream()
                .filter(l -> l.startsWith("ERROR") || l.startsWith("FATAL"))
                .toList();
        assertEquals(List.of(), cleanIssues, "valid patient produced errors");

        // note: enum-invalid values are rejected by the PARSER (HAPI-1821),
        // so the validation probe uses a structurally incomplete resource
        String broken = "{\"resourceType\":\"Observation\",\"id\":\"o1\"}";
        List<String> issues = r4.validate(broken).stream()
                .filter(l -> l.startsWith("ERROR") || l.startsWith("FATAL"))
                .toList();
        assertTrue(issues.stream().anyMatch(l -> l.contains("status") || l.contains("code")),
                "missing Observation.status/code not flagged: " + issues);
    }

    @Test
    @Timeout(60)
    void scenarioD_isolation() throws Exception {
        FhirPersonality r4 = personality("R4");
        FhirPersonality r5 = personality("R5");
        assertTrue(r4.canLoad("org.hl7.fhir.r4.model.Patient"));
        assertTrue(r5.canLoad("org.hl7.fhir.r5.model.Patient"));

        // FINDING: the HL7 validator core is internally R5-based, so the R4
        // *validation* stack legitimately contains org.hl7.fhir.r5 model
        // classes. Isolation therefore means SEPARATE PRIVATE COPIES, not
        // absence: the same FQCN must resolve to different classloaders in
        // the two bundles, and to nothing in the host.
        String r4Origin = r4.classOrigin("ca.uhn.fhir.context.FhirContext");
        String r5Origin = r5.classOrigin("ca.uhn.fhir.context.FhirContext");
        assertFalse(r4Origin.equals("absent") || r5Origin.equals("absent"));
        assertFalse(r4Origin.equals(r5Origin),
                "both bundles share one HAPI base copy — not private");

        String r4R5Model = r4.classOrigin("org.hl7.fhir.r5.model.Patient");
        String r5R5Model = r5.classOrigin("org.hl7.fhir.r5.model.Patient");
        assertFalse(r4R5Model.equals(r5R5Model),
                "R5 model class shared between bundles — not private");

        // host must see none of it
        assertFalse(canLoadInHost("org.hl7.fhir.r4.model.Patient"));
        assertFalse(canLoadInHost("org.hl7.fhir.r5.model.Patient"));
        assertFalse(canLoadInHost("ca.uhn.fhir.context.FhirContext"));
    }

    @Test
    @Timeout(60)
    void scenarioE_footprintMetrics() throws Exception {
        long r4Size = new File(System.getProperty("spike.r4.jar")).length();
        long r5Size = new File(System.getProperty("spike.r5.jar")).length();
        long r4Init = personality("R4").contextInitMillis();
        long r5Init = personality("R5").contextInitMillis();
        System.out.printf("METRICS bundle-size r4=%.1fMB r5=%.1fMB context-init r4=%dms r5=%dms%n",
                r4Size / 1e6, r5Size / 1e6, r4Init, r5Init);
        assertTrue(r4Init >= 0 && r5Init >= 0);
    }

    private boolean canLoadInHost(String fqcn) {
        try {
            Class.forName(fqcn, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
