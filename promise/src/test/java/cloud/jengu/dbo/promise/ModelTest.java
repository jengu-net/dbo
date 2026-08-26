package cloud.jengu.dbo.promise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {

    @Test
    @DisplayName("a catalogue is read whole: planned constants and gaps nobody cites are in the model")
    void readWhole() {
        Registry.Model model = Registry.of(TestPromises.class, TestStories.class).model();
        List<String> codes = model.promises().stream().map(model::codeOf).toList();
        assertTrue(codes.contains("REQ-TEST-PLAIN-PROMISE"), codes.toString());
        assertTrue(codes.contains("REQ-TEST-BODIED-PROMISE"),
                "a promise no classification declares and no test cites is still in the model");
        assertTrue(codes.stream().anyMatch(c -> c.startsWith("US-TEST-GAP-")),
                "the gap registers through the classification that declared it, "
                        + "qualified by that catalogue's namespace: " + codes);
    }

    @Test
    @DisplayName("the classification link is inverted once, derived never declared")
    void downLinksOnly() {
        Registry.Model model = Registry.of(TestPromises.class, TestStories.class).model();
        assertEquals(List.of(TestStories.FIRST_CASE),
                model.declaring(TestPromises.PLAIN_PROMISE));
        assertEquals(List.of(), model.declaring(TestPromises.BODIED_PROMISE),
                "an undeclared promise has no classifications, and that is an answer");
    }

    @Test
    @DisplayName("same-code areas merge; the merged area covers both catalogues' ground")
    void areasMergeByCode() {
        Registry.Model model = Registry.of(
                TestStories.class, TestAreas.class, TestAreasElsewhere.class).model();
        List<Area> areas = model.areas();
        assertEquals(2, areas.size(), areas.toString());
        Area shared = areas.stream()
                .filter(a -> a.code().equals("AREA-SHARED-CONCERN")).findFirst().orElseThrow();
        assertEquals("one concern", shared.title());
        assertEquals(List.of(TestStories.FIRST_CASE), shared.covers());
    }

    @Test
    @DisplayName("two same-code areas with conflicting titles are refused, both named")
    void conflictingAreasRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Registry.of(TestAreas.class, TestAreasConflicting.class).model());
        assertTrue(refused.getMessage().contains("only declared here")
                        && refused.getMessage().contains("the same code told differently"),
                refused.getMessage());
    }

    @Test
    @DisplayName("a class that is not a catalogue is refused at registration, saying why")
    void nonCataloguesRefused() {
        assertThrows(IllegalArgumentException.class, () -> Registry.of(String.class));
        assertThrows(IllegalArgumentException.class, () -> Registry.of(PromiseStatus.class),
                "an enum without @Catalogue has no namespace to derive codes from");
    }
}
