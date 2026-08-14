package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.api.ValueKind;

/**
 * SQL expression fragments over validated identifiers only. Envelope paths
 * pass {@code Paths.requireValid}; domain/type names pass registration
 * patterns; values never appear here.
 */
final class Sql {

    private Sql() {}

    /** Typed extraction of the first value at an envelope path, matching sort/filter/index casts. */
    static String typedPathExpression(String path, ValueKind kind) {
        String raw = "envelope #>> '{%s,0,v}'".formatted(path);
        return switch (kind) {
            case NUMBER -> "(" + raw + ")::numeric";
            case DATE -> "(" + raw + ")::timestamptz";
            case STRING, TOKEN, REFERENCE -> "(" + raw + ")";
        };
    }
}
