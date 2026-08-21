package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.RecordProjection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An R4 face serves AuditEvent as R4 (#90): what a client posts is what it
 * reads back, except the two facts the container owns.
 *
 * <p>REQ-DBO-POL-CUSTOM-AUDIT-EVENTS — the trail can be enriched — and
 * REQ-DBO-POL-ACTOR-FROM-AUTHORITY — never impersonated or backdated.
 */
class ContributedAuditEventTest {

    /** A posted AuditEvent in a consumer's own vocabulary, not dbo's. */
    private static final String POSTED = """
            {"resourceType":"AuditEvent",
             "type":{"system":"https://jengu.cloud/audit-type","code":"tenant.secret.rotated"},
             "action":"E",
             "recorded":"2001-01-01T00:00:00Z",
             "agent":[{"who":{"display":"Albus Dumbledore"},"requestor":true}],
             "source":{"site":"hogwarts","observer":{"display":"jengu-platform"}},
             "entity":[{"what":{"reference":"Organization/abc"}}],
             "extension":[{"url":"https://jengu.cloud/ext/audit-received-at",
                           "valueInstant":"2026-08-21T10:00:00Z"}]}""";

    private final RecordProjection projection =
            (RecordProjection) ElementVersion.of("r4").face()
                    .require(RecordProjection.class);

    private static RecordProjection.Record recorded(RecordProjection.Posted posted) {
        // what the engine writes: its own facts, plus the contribution it
        // carries without reading — base64, exactly as AuditModel stores it
        String entry = "{\"actor\":\"tenant-bootstrap\",\"interaction\":\"custom\""
                + ",\"targetType\":\"Organization\",\"targetId\":\"abc\""
                + ",\"code\":\"" + posted.code() + "\",\"outcome\":\"ok\""
                + ",\"contributed\":\""
                + Base64.getEncoder().encodeToString(posted.contributed()) + "\""
                + ",\"at\":\"2026-08-21T11:22:33Z\"}";
        return new RecordProjection.Record("AuditEntry", "entry-1", 1,
                entry.getBytes(StandardCharsets.UTF_8), java.util.List.of());
    }

    @Test
    void whatWasPostedComesBack() {
        Optional<RecordProjection.Posted> posted =
                projection.readPosted("AuditEntry", POSTED);
        assertTrue(posted.isPresent(), "an R4 face reads a posted AuditEvent");
        String rendered = projection.project(recorded(posted.get())).orElseThrow();

        assertTrue(rendered.contains("https://jengu.cloud/audit-type"),
                "the poster's own coding system survives — dbo does not rename it: " + rendered);
        assertTrue(rendered.contains("tenant.secret.rotated"), rendered);
        assertTrue(rendered.contains("\"site\":\"hogwarts\""),
                "source.site is the poster's and comes back: " + rendered);
        assertTrue(rendered.contains("jengu-platform"),
                "the poster's observer is not replaced by dbo: " + rendered);
        assertTrue(rendered.contains("https://jengu.cloud/ext/audit-received-at"),
                "extensions survive: " + rendered);
        assertTrue(rendered.contains("Organization/abc"), rendered);
    }

    @Test
    void whoAndWhenAreTheContainersWhateverWasClaimed() {
        RecordProjection.Posted posted =
                projection.readPosted("AuditEntry", POSTED).orElseThrow();
        String rendered = projection.project(recorded(posted)).orElseThrow();

        assertTrue(rendered.contains("2026-08-21T11:22:33Z"),
                "recorded is the container's clock: " + rendered);
        assertTrue(!rendered.contains("2001-01-01"),
                "a backdated claim does not survive: " + rendered);
        assertTrue(rendered.contains("tenant-bootstrap"),
                "the agent is who the container authenticated: " + rendered);
        assertTrue(!rendered.contains("Albus Dumbledore"),
                "a claim about who acted is not evidence, so it is replaced: " + rendered);
        assertEquals("\"entry-1\"", "\"" + "entry-1" + "\"");
        assertTrue(rendered.contains("entry-1"), "the id is the store's: " + rendered);
    }
}
