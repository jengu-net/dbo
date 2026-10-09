package cloud.jengu.dbo.spring.worker;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * The jar is here and no worker was asked for, said once at startup, for the
 * reason {@code DboServerNotAskedFor} says it.
 */
@AutoConfiguration(after = DboWorkerAutoConfiguration.class)
@ConditionalOnMissingBean(DboWorkerProperties.class)
public class DboWorkerNotAskedFor {

    public DboWorkerNotAskedFor() {
        org.slf4j.LoggerFactory.getLogger("dbo.worker").info("dbo-spring-boot-worker is on the "
                + "classpath and no worker was asked for: annotate the application with @{}, or "
                + "declare a {} bean", EnableDboWorker.class.getSimpleName(),
                DboWorkerProperties.class.getSimpleName());
    }
}
