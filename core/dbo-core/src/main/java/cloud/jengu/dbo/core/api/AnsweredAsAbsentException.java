package cloud.jengu.dbo.core.api;

/**
 * A request about a record the caller may not know is there, answered as a
 * request about one that is not.
 *
 * <p>A refusal would confirm the record exists, which is what keeping it
 * out of reach is for. The message names only what the caller already named.
 */
public class AnsweredAsAbsentException extends RuntimeException {

    public AnsweredAsAbsentException(String typeName, String id) {
        super(typeName + "/" + id);
    }
}
