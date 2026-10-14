package cloud.jengu.dbo.tenant;

/**
 * A string as a JSON string literal, for answers the step surfaces write by
 * hand.
 *
 * <p>What a caller sent comes back in a refusal — a step's name, an act it
 * asked for — so every character that could end the string or the document is
 * escaped, and so are {@code <}, {@code >} and {@code &}: an answer is never
 * read as markup, whatever reads it and whatever it was told the type is.
 */
final class JsonText {

    private JsonText() {
    }

    static String quote(String value) {
        String text = String.valueOf(value);
        StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' '
                            || c == ' ') {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
