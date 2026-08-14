package cloud.jengu.dbo.core.api;

/** The type was never registered — the contract fails closed (§12). */
public class UnknownTypeException extends RuntimeException {

    public UnknownTypeException(String typeName) {
        super("type not registered: " + typeName);
    }
}
