package cloud.jengu.dbo.core.api;

import java.util.regex.Pattern;

/**
 * Envelope path names are code-owned and restricted, so they can appear
 * inside SQL expression indexes without ever being an injection surface
 * (defense in depth on top of REQ-DBO-CORE-PARAMETERIZED-SQL).
 */
public final class Paths {

    private static final Pattern VALID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private Paths() {}

    public static void requireValid(String path) {
        if (path == null || !VALID.matcher(path).matches()) {
            throw new IllegalArgumentException("invalid envelope path: " + path);
        }
    }
}
