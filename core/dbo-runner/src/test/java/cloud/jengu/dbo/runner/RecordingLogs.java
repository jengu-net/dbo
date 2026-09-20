package cloud.jengu.dbo.runner;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The binding these tests run against: every event, kept.
 *
 * <p>Written here rather than pulled in, because the alternative is a logging
 * dependency on a module whose whole point is that it has almost none, and
 * what is needed is a list.
 */
public final class RecordingLogs implements SLF4JServiceProvider {

    /** Every event any logger in this module recorded, as "LEVEL message | throwable". */
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private final IMarkerFactory markers = new BasicMarkerFactory();
    private final MDCAdapter mdc = new BasicMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return name -> new LegacyAbstractLogger() {

            @Override
            protected String getFullyQualifiedCallerName() {
                return null;
            }

            @Override
            protected void handleNormalizedLoggingCall(Level level, Marker marker, String message,
                    Object[] arguments, Throwable thrown) {
                EVENTS.add(level + " " + message + " " + java.util.Arrays.toString(arguments)
                        + " | " + thrown);
            }

            @Override
            public boolean isTraceEnabled() {
                return true;
            }

            @Override
            public boolean isDebugEnabled() {
                return true;
            }

            @Override
            public boolean isInfoEnabled() {
                return true;
            }

            @Override
            public boolean isWarnEnabled() {
                return true;
            }

            @Override
            public boolean isErrorEnabled() {
                return true;
            }
        };
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markers;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdc;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
    }
}
