package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import org.junit.jupiter.api.Test;

import java.util.List;

/** TEMPORARY probe: what do the per-version singletons actually retain? */
class HeapProbeTest {

    private static long settled() {
        for (int i = 0; i < 6; i++) {
            System.gc();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    @Test
    void whatEachVersionRetains() {
        long base = settled();
        System.out.println("PROBE baseline            = " + base + "M");

        R4Personality r4 = new R4Personality(List.of(FhirTypeConfig.internal("Patient")));
        r4.validate("{\"resourceType\":\"Patient\",\"id\":\"x\"}");
        long afterR4 = settled();
        System.out.println("PROBE after R4 ctx+valid  = " + afterR4 + "M  (+"
                + (afterR4 - base) + "M)");

        R5Personality r5 = new R5Personality(List.of(FhirTypeConfig.internal("Patient")));
        r5.validate("{\"resourceType\":\"Patient\",\"id\":\"x\"}");
        long afterR5 = settled();
        System.out.println("PROBE after R5 ctx+valid  = " + afterR5 + "M  (+"
                + (afterR5 - afterR4) + "M)");
        System.out.println("PROBE both versions total = " + (afterR5 - base) + "M");
    }
}
