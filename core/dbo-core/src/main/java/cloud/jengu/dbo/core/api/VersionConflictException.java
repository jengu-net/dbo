package cloud.jengu.dbo.core.api;

/** Optimistic concurrency failure — maps to HTTP 412 at the FHIR surface. */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(String typeName, String id, long expected, long actual) {
        super("version conflict on %s/%s: expected %d, found %d".formatted(typeName, id, expected, actual));
    }
}
