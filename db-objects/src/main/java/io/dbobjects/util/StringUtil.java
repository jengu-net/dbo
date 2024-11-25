package io.dbobjects.util;

public class StringUtil {

    public static String truncate(String text, int length, boolean truncateFromEnd) {
        if (text == null || text.length() <= length) {
            return text;
        } else {
            length = length - 3;
            return truncateFromEnd ? text.substring(0, length) + "..." : "..." + text.substring(text.length() - length);
        }
    }

    public static String removeWs(Object s) {
        return s == null ? null : s.toString().replace("\n", "").replace("\r", "");
    }
}
