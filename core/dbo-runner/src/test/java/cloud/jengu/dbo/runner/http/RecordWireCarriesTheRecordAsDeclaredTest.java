package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire carries the record as it is declared, not as somebody wrote it out
 * once.
 *
 * <p>{@code Run} grew milestones and named inputs after it
 * existed, and a field-by-field encoder written before either would have
 * dropped both — silently, at the far end, where it reads as a step that
 * reported nothing rather than as an encoder that forgot. So the test asserts
 * against the record's own component list: add a component and this fails
 * here, which is the cheapest place for it to fail.
 */
class RecordWireCarriesTheRecordAsDeclaredTest {

    private static final Run FULL = new Run("run-1", 7L, "dbo.lab/validate/1", "dbo.lab",
            "validate", RunKind.PIPELINE, Holder.AUTOMATION, "parent-1", "correlation-1",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            Map.of("read", 4L), new Run.Item("Observation/o1", Failure.RECORD, "no such code"),
            List.of("r4"),
            new Run.Assignment(Scope.zone("ee"),
                    new Executor("bench-7", "1.2", "cloud.jengu.test", Scope.BASELINE),
                    "the step's own", Instant.parse("2026-08-29T10:15:30Z")),
            new Run.Produced(List.of("Observation/o1/2"), Map.of("Observation", 2L), 1L),
            "1.0", Map.of("specimen", "Specimen/s1"),
            new Run.Milestone("validated", 2, 3));

    @Test
    @DisplayName("every component a run declares survives the wire, and a new one fails here")
    void aRunSurvivesAsDeclared() {
        Run back = RecordWire.decode(RecordWire.read(RecordWire.write(FULL)), Run.class);

        List<String> dropped = new ArrayList<>();
        for (RecordComponent component : Run.class.getRecordComponents()) {
            try {
                Object before = component.getAccessor().invoke(FULL);
                Object after = component.getAccessor().invoke(back);
                if (!java.util.Objects.equals(before, after)) {
                    dropped.add(component.getName() + ": " + before + " → " + after);
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        assertEquals(List.of(), dropped, "a component of Run did not survive the wire");
    }

    @Test
    @DisplayName("a step declaration's Optionals and sets travel as themselves")
    void aStepDeclarationSurvives() {
        StepDeclaration declared = StepDeclaration.of("dbo.lab.validate", "1.0", "r4")
                .consuming("http://example.test/Specimen")
                .containing("open", "close")
                .taking("specimen", "http://example.test/Specimen")
                .reaching("parsed", "validated");

        StepDeclaration back = RecordWire.decode(RecordWire.read(RecordWire.write(declared)),
                StepDeclaration.class);

        assertEquals(declared.id(), back.id());
        assertEquals(Optional.of("http://example.test/Specimen"), back.consumes());
        // Empty, not null: an Optional that decoded to null would throw at the
        // far side's first read, one call away from where the wire lost it.
        assertEquals(Optional.empty(), back.produces());
        assertEquals(Set.of("open", "close"), back.actions());
        assertEquals(declared.slots(), back.slots());
        assertEquals(List.of("parsed", "validated"), back.milestones());
    }

    @Test
    @DisplayName("a stored object's payload travels as bytes, unparsed")
    void aStoredObjectKeepsItsBytes() {
        byte[] payload = "{\"resourceType\":\"Specimen\",\"id\":\"s1\"}"
                .getBytes(StandardCharsets.UTF_8);
        StoredObject object = new StoredObject("s1", "Specimen", 3L,
                Instant.parse("2026-08-29T09:00:00Z"), payload, false, "r4");

        StoredObject back = RecordWire.decode(RecordWire.read(RecordWire.write(object)),
                StoredObject.class);

        assertArrayEquals(payload, back.payload(),
                "the bytes travel as bytes — parsing on the way past drops what the "
                        + "model has no field for");
        assertEquals(object.lastUpdated(), back.lastUpdated());
        assertEquals(object.typeName(), back.typeName());
    }

    @Test
    @DisplayName("a declaration's vitals travel, and a field this side has not learned is "
            + "ignored rather than refused")
    void aDeclarationSurvivesAndTheFutureIsTolerated() {
        Declarations.Declared declared = new Declarations.Declared("dbo.lab", "validate",
                "bench-7", "1.2", "cloud.jengu.test", Scope.BASELINE, "consumer-7")
                .withVitals(Map.of("queue", "0"));

        String wire = RecordWire.write(declared);
        String ahead = wire.substring(0, wire.length() - 1) + ",\"whatComesNext\":\"a value\"}";
        Declarations.Declared back =
                RecordWire.decode(RecordWire.read(ahead), Declarations.Declared.class);

        assertEquals(declared, back, "a peer one version ahead is not a broken peer");
        assertTrue(back.metadata().containsKey("queue"), "and the vitals it did carry arrived");
    }
}
