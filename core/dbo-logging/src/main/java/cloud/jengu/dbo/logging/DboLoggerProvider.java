package cloud.jengu.dbo.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one binding every bundle in the runtime shares.
 *
 * <p>Found by slf4j through {@code ServiceLoader}; in OSGi this fragment is
 * attached to the slf4j-api bundle, so the service entry sits on the API's own
 * classpath and every importer resolves here.
 *
 * <p>The MDC adapter is a no-op on purpose. MDC exists to attach ambient
 * context to every line, and on this product the ambient context is a tenant
 * and a subject — precisely what must not be written beside a message. A
 * caller that wants a tenant code in a line puts it in the line.
 */
public final class DboLoggerProvider implements SLF4JServiceProvider {

    /** The slf4j API this binding was written against. */
    private static final String API_VERSION = "2.0.99";

    private final Map<String, DboLogger> loggers = new ConcurrentHashMap<>();
    private final IMarkerFactory markers = new BasicMarkerFactory();
    private final MDCAdapter mdc = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return name -> loggers.computeIfAbsent(name, DboLogger::new);
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
        return API_VERSION;
    }

    @Override
    public void initialize() {
    }
}
