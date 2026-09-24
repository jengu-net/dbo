package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.tenant.api.TenantPoint;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Which point in a tenant's life this bean wants to be told about.
 *
 * <p>The runtime selects a listener on a property, and the whiteboard refuses
 * a registration that does not carry one. Beside the class rather than in a
 * registration call, because a Spring author expects to read a bean's
 * metadata where the bean is — and the attributes are the property names, one
 * to one, so the mapping is legible rather than translated.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DboTenantListener {

    /** The point this listener runs at. */
    TenantPoint point();

    /**
     * Which tenants it is for, as a filter over what a tenant declares.
     * Empty means every tenant.
     */
    String target() default "";
}
