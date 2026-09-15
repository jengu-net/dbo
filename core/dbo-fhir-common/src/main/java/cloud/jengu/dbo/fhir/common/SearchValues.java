package cloud.jengu.dbo.fhir.common;

import java.util.ArrayList;
import java.util.List;

/**
 * How a search parameter's value is read before any type knows what it means.
 *
 * <p>Here rather than in one compiler because there are three — the element
 * face serves every search, and each personality compiles the filters a
 * subscription carries — and this is the layer where they must agree. They
 * did not: the comma was read as part of the value in all three, so several
 * values were looked for as one literal that nothing carries, and a
 * subscription filtered that way simply never fired.
 */
public final class SearchValues {

    private SearchValues() {
    }

    /**
     * A value split on its commas, which is what FHIR means by OR.
     *
     * <p>A backslash escapes one, because a value may legitimately contain a
     * comma — a family name written "Smith, John" is a search somebody makes,
     * and splitting it would look for two people who do not exist. The escape
     * is removed here, so what reaches the store is the value the caller
     * meant rather than the one they had to spell.
     */
    public static List<String> several(String value) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int at = 0; at < value.length(); at++) {
            char c = value.charAt(at);
            if (c == '\\' && at + 1 < value.length()) {
                current.append(value.charAt(++at));
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return List.copyOf(values);
    }

    /**
     * Refuses a comma where the union it asks for cannot be stated.
     *
     * <p>Named rather than dropped, and named rather than read as one literal
     * value — which is what used to happen everywhere, and it answered
     * nothing while looking like an ordinary empty result.
     */
    public static void noSeveral(String typeName, String display, String value) {
        if (several(value).size() > 1) {
            throw new UnknownSearchParameterException(typeName, display + "=" + value
                    + " (several values are not served for this parameter type)");
        }
    }
}
