package cloud.jengu.dbo.spring.server;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This application serves a store.
 *
 * <p>Asked for rather than implied by the jar, because a host has contexts
 * that must not boot one: a test slice, a context runner, a tool sharing the
 * classpath. Without this, and without a {@link DboServerProperties} bean of
 * the application's own, the assembly configures nothing and needs no
 * {@code dbo.*} property.
 *
 * <p>It binds {@code dbo.*} and nothing else; the store itself comes from the
 * auto-configuration, so a test slice that loads a class carrying this still
 * gets no store. An application that builds its properties in code — from a
 * container a test started, say — declares that bean instead of this, and
 * never both: two of them are two deployments' worth of configuration and the
 * store is one.
 *
 * <p>There is no attribute for serving without an authority. That is
 * {@code dbo.auth.disabled=true}, a property, so that it lives in a test's
 * configuration and cannot ship in an application's code.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EnableConfigurationProperties(DboServerProperties.class)
public @interface EnableDboServer {
}
