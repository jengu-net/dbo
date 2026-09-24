package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.tenant.api.TenantDomain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Which of a tenant's streams this bean reads, and as whom.
 *
 * <p>An observer is a named durable consumer rather than a callback, and the
 * name is the whole reason: a consumer that was absent for an hour resumes
 * where it left off instead of missing the hour. So the name is required, and
 * a bean that does not give one is refused rather than quietly reading
 * nothing — a consumer that silently observes nothing is indistinguishable
 * from one that is working and has nothing to do.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DboObserver {

    /** The stream this observer reads. */
    TenantDomain domain();

    /**
     * The durable consumer it reads as.
     *
     * <p>Chosen by the application and kept: changing it starts again from
     * the beginning of the stream, and two applications sharing one takes
     * each other's changes.
     */
    String consumer();

    /**
     * Which tenants it is for, as a filter over what a tenant declares.
     * Empty means every tenant.
     */
    String target() default "";
}
