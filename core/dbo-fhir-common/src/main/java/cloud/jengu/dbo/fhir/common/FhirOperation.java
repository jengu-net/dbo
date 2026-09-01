package cloud.jengu.dbo.fhir.common;

import java.util.Map;
import java.util.Set;

/**
 * One FHIR operation, described by whatever answers it.
 *
 * <p>A registration is the only way an operation becomes reachable, and the
 * CapabilityStatement is generated from the same registrations — so an
 * operation cannot be served and undeclared. It used to be able to: `$expand`,
 * `$lookup`, `$validate-code` and `$validate` were all routed by a hand-written
 * branch and announced by nothing, because routing and declaring were two lists
 * that had to agree.
 *
 * <p>Absence needs no flag either. A terminology facade that was never wired
 * registers nothing, so its operations are neither routed nor declared, and
 * nobody has to tell the capability generator what the router would have done.
 *
 * <p>Lives in the FHIR face family rather than the engine: an
 * {@code OperationDefinition} canonical and a {@code $}-name are healthcare
 * vocabulary, and the engine does not know what {@code $expand} is.
 */
public interface FhirOperation {

    /** The operation's name without the {@code $} — {@code "validate"}. */
    String name();

    /** Canonical URL of its {@code OperationDefinition}, for the statement. */
    String definition();

    /**
     * The resource types it applies to. Empty means system-level: an operation
     * at the base rather than on a type.
     */
    Set<String> types();

    /**
     * Answers the call.
     *
     * @param typeName the type it was invoked on, or null at system level
     * @param query    decoded query parameters
     * @param body     the request body, empty for a GET
     */
    Answer answer(String typeName, Map<String, String> query, String body);

    /**
     * What to send back.
     *
     * <p>An operation names its own status rather than letting the router infer
     * one: {@code $validate} answers 200 for a resource it rejects — the
     * question was asked correctly — while {@code $expand} answers 404 for a
     * ValueSet nobody registered. A router guessing between those would get one
     * of them wrong.
     */
    record Answer(int status, String body) {

        public static Answer ok(String body) {
            return new Answer(200, body);
        }

        public static Answer status(int status, String body) {
            return new Answer(status, body);
        }
    }
}
