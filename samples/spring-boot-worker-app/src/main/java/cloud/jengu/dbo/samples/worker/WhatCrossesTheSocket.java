package cloud.jengu.dbo.samples.worker;

import java.util.Locale;

/**
 * One message on the socket between a worker and the clinic's door.
 *
 * <p>The stream's asks, its answers, an answer the door held, and a wake-up,
 * each under the key it travels by: a line saying which it is and the key,
 * then the carried text exactly as it was handed over. The text is the store's
 * — an ask signed over its own bytes, an answer keyed by the ask — and nothing
 * here reads it. A frame that changed it would deliver a forgery, which the
 * door refuses.
 *
 * <p>Both ends of the sample's carrier speak this, so it lives with the worker
 * and the clinic's application, which depends on the worker, reads it from
 * here.
 */
// --8<-- [start:frame]
public record WhatCrossesTheSocket(Kind kind, String key, String text) {

    /** What a message is: the worker asks and collects, the door answers, hands over and wakes. */
    public enum Kind { ASK, ANSWER, COLLECT, HELD, GONE, WAKE }

    public static WhatCrossesTheSocket of(Kind kind, String key, String text) {
        return new WhatCrossesTheSocket(kind, key, text == null ? "" : text);
    }

    /** The message as it travels: {@code <kind> <key>}, a newline, and the text untouched. */
    public String written() {
        return kind.name().toLowerCase(Locale.ROOT) + " " + key + "\n" + text;
    }

    /** A message as it arrived; the text after the first line is handed on as it is. */
    public static WhatCrossesTheSocket read(String message) {
        int newline = message.indexOf('\n');
        String head = newline < 0 ? message : message.substring(0, newline);
        int space = head.indexOf(' ');
        if (space < 0) {
            throw new IllegalArgumentException("a message on the socket starts with what it is "
                    + "and the key it travels by");
        }
        return new WhatCrossesTheSocket(
                Kind.valueOf(head.substring(0, space).toUpperCase(Locale.ROOT)),
                head.substring(space + 1), newline < 0 ? "" : message.substring(newline + 1));
    }
}
// --8<-- [end:frame]
