package cloud.jengu.dbo.maintenance;

import java.util.regex.Pattern;

/** Identifier validation + minimal JSON string quoting for archive metadata. */
final class Names {

    private static final Pattern DOMAIN = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    private Names() {}

    static void requireDomain(String domain) {
        if (domain == null || !DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("invalid domain: " + domain);
        }
    }

    /**
     * NDJSON-safe single-lining: in valid JSON, literal newlines occur only
     * BETWEEN tokens (inside strings they are escaped), so replacing them
     * with spaces preserves the document.
     */
    static String flatten(String json) {
        return json.replace('\r', ' ').replace('\n', ' ');
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
