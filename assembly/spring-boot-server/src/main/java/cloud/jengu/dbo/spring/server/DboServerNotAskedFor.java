package cloud.jengu.dbo.spring.server;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * The jar is here and no store was asked for, said once at startup.
 *
 * <p>Not a refusal: a test slice or a tool on the application's classpath is
 * exactly the context that should have no store. Said because the other
 * reading of the same silence is an application that meant to serve and
 * forgot to ask.
 */
@AutoConfiguration(after = DboServerAutoConfiguration.class)
@ConditionalOnMissingBean(DboServerProperties.class)
public class DboServerNotAskedFor {

    public DboServerNotAskedFor() {
        org.slf4j.LoggerFactory.getLogger("dbo.server").info("dbo-spring-boot-server is on the "
                + "classpath and no store was asked for: annotate the application with @{}, or "
                + "declare a {} bean", EnableDboServer.class.getSimpleName(),
                DboServerProperties.class.getSimpleName());
    }
}
