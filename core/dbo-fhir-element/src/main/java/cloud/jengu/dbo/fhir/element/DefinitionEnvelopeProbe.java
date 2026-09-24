package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.fhir.index.DefinitionRows;

import java.util.List;

/**
 * A test's way to the two front ends of {@link DefinitionEnvelopes}.
 *
 * <p>The envelope a definition type is indexed by can be built from
 * expressions the face writes out or from the parameters the cut compiled,
 * and the claim worth proving is that the two agree. Proving it needs a
 * tenant, so the test that does it lives in the harness and cannot see a
 * package-private class.
 *
 * <p>A probe rather than a widening, as {@code SecretHashProbe} is for the
 * same reason: making the builder public to test it would put two front ends
 * and their whole vocabulary on this store's exported surface, where the
 * ledger would then hold the project to them. What is exported should be
 * what a consumer is meant to call.
 */
public final class DefinitionEnvelopeProbe {

    private DefinitionEnvelopeProbe() {
    }

    /** The envelope from the expressions the face writes out. */
    public static Envelope fromTheFace(String typeName, byte[] payload) {
        return DefinitionEnvelopes.extract(DefinitionParameters.forType("r4", typeName),
                typeName, payload, true);
    }

    /** The envelope from the parameters the cut compiled. */
    public static Envelope fromTheRows(List<DefinitionRows.Parameter> compiled, String typeName,
            byte[] payload, List<String> declined) {
        return DefinitionEnvelopes.extract(compiled, typeName, payload, true, declined);
    }
}
