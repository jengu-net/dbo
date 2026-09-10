package cloud.jengu.dbo.core.api;

import java.util.List;
import java.util.Map;

/**
 * One object a store holds, said without its payload: the id and version,
 * the identifiers it is findable by, and the first value under each envelope
 * path the caller asked about.
 *
 * <p>The shape of an inventory rather than of a read. A face taking stock of
 * the definitions a tenant holds needs to know each one is there, what it is
 * called and which version it says it is — not its body, which for two
 * thousand definitions is tens of megabytes it would then throw away.
 */
public record Held(String id, long versionId, List<Identifier> identifiers,
        Map<String, EnvelopeValue> firsts) {
}
