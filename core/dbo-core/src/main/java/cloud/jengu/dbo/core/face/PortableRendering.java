package cloud.jengu.dbo.core.face;

/**
 * Renders a stored object as the domain's own interchange shape — for
 * healthcare, one FHIR resource per line (jengu-platform#866).
 *
 * <p>The engine stores payloads and knows nothing about them. It cannot put an
 * id back on a resource, because "resources have an id, in a field called
 * {@code id}, and a version in {@code meta.versionId}" is knowledge about
 * FHIR. So an export that has to hand somebody a file their own tools can open
 * asks the face for the shape, and refuses to produce one if the face does not
 * offer it — a private format labelled portable is worse than no export.
 *
 * <p>This is why the payload alone is not enough. dbo stores what it was sent:
 * a create with no id in the body is stored exactly as sent, which is the
 * point of payload-is-truth. A reader outside this system needs the id and the
 * version put back, exactly as the read surface puts them back — the same
 * projection every client already sees, not a rewrite of stored truth.
 */
@FunctionalInterface
public interface PortableRendering {

    /**
     * @param payload      the stored bytes, untouched
     * @param id           the object's id, which the payload does not carry
     * @param versionId    the version, which the payload does not carry either
     * @return one line of the domain's interchange format, without a newline
     */
    String render(byte[] payload, String id, long versionId);
}
