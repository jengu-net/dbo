package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunChain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A run's chain read back from the tenant's trail: every travel and access
 * entry recorded under the run, each carrying the link it was written with.
 *
 * <p>The link lives on the entry, so this is a read of the trail and not of
 * a second structure — the chain's lifetime is the trail's, and an entry
 * retention pruned is simply absent here, which the chain reads as
 * unchained rather than broken.
 */
final class TrailLinks {

    private TrailLinks() {}

    static List<RunChain.Link> of(ObjectStore engine, Run run) {
        List<RunChain.Link> links = new ArrayList<>();
        for (StoredObject entry : engine.select(Criteria.of("AuditEntry")
                .eq("run", EnvelopeValue.of(run.key())))) {
            Object node = Json.parse(new String(entry.payload(), StandardCharsets.UTF_8));
            if (!(node instanceof Map<?, ?> map) || !(map.get("detail") instanceof Map<?, ?> detail)
                    || detail.get("link") == null) {
                // An entry under the run that carries no link — a run-occasioned
                // read, say — is on the trail and not on the chain.
                continue;
            }
            String code = String.valueOf(map.get("code"));
            String author = String.valueOf(map.get("actor"));
            String subject = "travel".equals(code)
                    ? String.valueOf(detail.get("to"))
                    : map.get("targetType") + "/" + map.get("targetId");
            links.add(new RunChain.Link(code, String.valueOf(detail.get("previous")),
                    String.valueOf(detail.get("link")), author, subject,
                    detail.get("signature") == null ? null : String.valueOf(detail.get("signature"))));
        }
        return links;
    }
}
