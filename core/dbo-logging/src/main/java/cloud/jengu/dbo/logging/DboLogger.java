package cloud.jengu.dbo.logging;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.io.PrintStream;
import java.time.Instant;

/**
 * One line per event, on stdout.
 *
 * <p>stdout rather than a file because both deployment targets collect it:
 * a container runtime and an init system each read a process's stdout, and a
 * log file inside a container is a log nobody sees.
 *
 * <p><b>The message is the whole record.</b> There is deliberately no MDC and
 * no structured argument capture beyond what a caller writes into the
 * message, and that is a product decision rather than a simplification: this
 * store encrypts identifying elements inside the payload, and a logging
 * framework that hoovers up context is exactly how a national identifier
 * reaches a log file with different retention and different access control
 * from the database it was protected in. What is not collected cannot leak.
 */
final class DboLogger extends LegacyAbstractLogger {

    private static final long serialVersionUID = 1L;

    /** Shared, because interleaved half-lines are worse than a lock. */
    private static final PrintStream OUT = System.out;

    DboLogger(String name) {
        this.name = name;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
            Object[] arguments, Throwable throwable) {
        String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
        synchronized (OUT) {
            if (DboLogging.json()) {
                // The stack goes INSIDE the object. A trace printed after the
                // line would be a dozen lines of not-JSON in a stream whose
                // whole contract is one object per line, and every parser
                // downstream would drop them.
                OUT.println(json(level, message, throwable));
            } else {
                OUT.println(text(level, message, throwable));
                if (throwable != null) {
                    throwable.printStackTrace(OUT);
                }
            }
            OUT.flush();
        }
    }

    private String json(Level level, String message, Throwable throwable) {
        StringBuilder b = new StringBuilder(128);
        b.append("{\"ts\":\"").append(Instant.now()).append('"')
                .append(",\"level\":\"").append(level).append('"')
                .append(",\"logger\":").append(quote(name))
                .append(",\"thread\":").append(quote(Thread.currentThread().getName()))
                .append(",\"message\":").append(quote(message));
        if (throwable != null) {
            b.append(",\"error\":").append(quote(throwable.toString()))
                    .append(",\"stack\":").append(quote(stackOf(throwable)));
        }
        return b.append('}').toString();
    }

    private String text(Level level, String message, Throwable throwable) {
        return Instant.now() + " " + level + " " + name + " - " + message
                + (throwable == null ? "" : " (" + throwable + ")");
    }

    private static String stackOf(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    // ---- level gates, from the one configured level --------------------

    @Override
    public boolean isTraceEnabled() {
        return DboLogging.enabled(DboLogging.Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
        return DboLogging.enabled(DboLogging.Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return DboLogging.enabled(DboLogging.Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return DboLogging.enabled(DboLogging.Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return DboLogging.enabled(DboLogging.Level.ERROR);
    }
}
