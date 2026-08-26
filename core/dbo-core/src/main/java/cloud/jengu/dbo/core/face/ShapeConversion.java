package cloud.jengu.dbo.core.face;

import java.util.Optional;

/**
 * Converting a payload from one shape major to the next — a face capability,
 * not an engine feature.
 *
 * <p>The engine owns the reshape LOOP unconditionally: the walk by stamp
 * bound, the paging, the rate bound, the cursor, the ordinary versioned
 * write. What it never owns is the transformation, because whether a model
 * can express its own converters as data is a fact about the model. FHIR
 * can — StructureMaps ship in the pack beside the shapes they convert — so
 * the FHIR face provides this. A face over a model with no in-data converter
 * standard does not, and that is a truth about the model rather than a
 * defect: the engine then refuses the operation by name instead of
 * half-running it.
 *
 * <p>A pure transformation, like every capability here: bytes in, bytes out,
 * no store and no request state.
 */
public interface ShapeConversion {

    /**
     * The payload converted to {@code targetMajor} of {@code profile}, or
     * empty when this face has no converter for that hop.
     *
     * <p>Empty is an ORDINARY ANSWER, not a failure: the loop names the
     * object, leaves it behind, and carries on — one object nobody can
     * convert must not strand the rest of a hospital's data. A converter
     * that exists and then fails throws, and the loop treats that the same
     * way, with the reason attached.
     *
     * @param typeName    the object's type, for a face whose payloads do not
     *                    say what they are
     * @param payload     the stored bytes, as the engine holds them
     * @param profile     the shape canonical the stamp names
     * @param targetMajor the major the caller is converging on
     */
    Optional<byte[]> convert(String typeName, byte[] payload, String profile, int targetMajor);
}
