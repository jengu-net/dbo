package cloud.jengu.dbo.core.api;

/**
 * The personality hook: derives the searchable envelope from an opaque
 * payload. The engine never interprets the payload itself
 * (REQ-DBO-CORE-PAYLOAD-IS-TRUTH).
 */
@FunctionalInterface
public interface EnvelopeExtractor {
    Envelope extract(String typeName, byte[] payload);
}
