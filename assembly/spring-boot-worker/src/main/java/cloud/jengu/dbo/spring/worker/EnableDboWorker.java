package cloud.jengu.dbo.spring.worker;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This application performs a store's work.
 *
 * <p>Asked for rather than implied by the jar, for the reason
 * {@code EnableDboServer} is: a host has contexts that must not boot a
 * container. Without this, and without a {@link DboWorkerProperties} bean of
 * the application's own, the assembly configures nothing.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EnableConfigurationProperties(DboWorkerProperties.class)
public @interface EnableDboWorker {
}
