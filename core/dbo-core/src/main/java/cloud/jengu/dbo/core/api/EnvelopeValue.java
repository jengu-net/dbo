package cloud.jengu.dbo.core.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A typed value in the searchable envelope. Typing is what makes numeric and
 * date ordering correct (REQ-DBO-SRCH-TYPED-ORDERING groundwork) — the legacy
 * engine's text-only ordering is the anti-pattern.
 */
public sealed interface EnvelopeValue {

    record Str(String value) implements EnvelopeValue {}

    record Num(BigDecimal value) implements EnvelopeValue {}

    record Date(Instant value) implements EnvelopeValue {}

    record Token(String system, String code) implements EnvelopeValue {}

    record Ref(String targetType, String targetId) implements EnvelopeValue {}

    static EnvelopeValue of(String v) { return new Str(v); }

    static EnvelopeValue of(long v) { return new Num(BigDecimal.valueOf(v)); }

    static EnvelopeValue of(BigDecimal v) { return new Num(v); }

    static EnvelopeValue of(Instant v) { return new Date(v); }

    static EnvelopeValue token(String system, String code) { return new Token(system, code); }
}
