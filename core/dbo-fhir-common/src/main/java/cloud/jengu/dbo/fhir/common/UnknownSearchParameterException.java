package cloud.jengu.dbo.fhir.common;

/**
 * Strict search (REQ-DBO-SRCH-STRICT-BY-DEFAULT): an unsupported parameter is
 * rejected, never silently ignored — a dropped filter returns a WRONG result
 * set, which in a clinical system is a safety issue.
 */
public class UnknownSearchParameterException extends RuntimeException {

    public UnknownSearchParameterException(String typeName, String param) {
        super("unsupported search parameter for %s: %s".formatted(typeName, param));
    }
}
