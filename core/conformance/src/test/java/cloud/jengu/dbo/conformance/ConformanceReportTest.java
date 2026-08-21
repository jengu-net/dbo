package cloud.jengu.dbo.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The committed report changes only when conformance changes (#84).
 *
 * <p>Two runs that observed the same behaviour through different ephemeral
 * ports and different generated ids must render byte-identical files — that
 * is the property the report's whole purpose ("a change shows up in a diff")
 * stands on, proven the way the report is meant to work: render twice, diff.
 */
class ConformanceReportTest {

    @TempDir
    Path dir;

    private static Conformance run(String base, String id) {
        Conformance conformance = new Conformance();
        conformance.check(Conformance.Area.values()[0], "`POST` creates and answers 201",
                "http.html#create", () -> "201, Location: " + base + "/fhir/Patient/" + id);
        return conformance;
    }

    @Test
    void twoRunsDifferingOnlyInVolatileValuesRenderIdentically() throws Exception {
        ConformanceReport.write(dir.resolve("a"), "R4",
                run("http://127.0.0.1:50671", "01a02357-57cf-741d-be79-c6e38029e0f8"));
        ConformanceReport.write(dir.resolve("b"), "R4",
                run("http://127.0.0.1:65001", "01a023d0-e18a-73ca-b90f-3a2e825708c6"));
        assertEquals(Files.readString(dir.resolve("a/r4.md")),
                Files.readString(dir.resolve("b/r4.md")),
                "a report that differs on an ephemeral port or a generated id would bury "
                        + "real conformance changes in churn");
    }
}
