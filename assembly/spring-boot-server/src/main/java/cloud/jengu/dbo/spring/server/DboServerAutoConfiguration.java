package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.embedded.DboRegistrar;
import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.embedded.FrameworkContribution;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.nio.file.Path;
import java.util.Map;

/**
 * Adding this jar makes the application a DBO node.
 *
 * <p>Tenants come up, their surfaces answer on the application's own port,
 * and beans that implement the extension points are the extension points.
 * Nothing this configuration publishes names a {@code Bundle}, a
 * {@code BundleContext} or a {@code ServiceReference}.
 */
@AutoConfiguration
@EnableConfigurationProperties(DboServerProperties.class)
public class DboServerAutoConfiguration {

    /**
     * The container, with what this deployment said.
     *
     * <p>Booted as the bean is created rather than on a lifecycle, so that
     * anything taking it takes a RUNNING container — including a bean of the
     * application's own that registers something while being constructed,
     * which is earlier than any lifecycle phase and impossible to order
     * against one.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public EmbeddedRuntime dboEmbeddedRuntime(DboServerProperties properties,
            ObjectProvider<FrameworkContribution> contributions) {
        refuseToServeWithoutAnAuthority(properties);
        // EVERY contribution, not this configuration's own. An application
        // that also performs work carries a second one, and the container it
        // reaches is this one — whichever half of the host happened to build
        // it.
        return new EmbeddedRuntime(getClass().getClassLoader(),
                FrameworkContribution.merged(contributions.orderedStream().toList()),
                EmbeddedRuntime.storageUnder(
                        Path.of(System.getProperty("java.io.tmpdir")), "dbo-embedded"));
    }

    /** What serving tells the container. */
    @Bean
    public FrameworkContribution dboServerFrameworkContribution(DboServerProperties properties) {
        return properties::asFrameworkProperties;
    }

    /**
     * The application's extension-point beans, on the whiteboard.
     *
     * <p>Before anything that lets a tenant come up, and the ordering is the
     * point rather than tidiness: a listener registered after a tenant
     * reached a point is never told about that tenant, and being told
     * nothing looks exactly like working.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public DboExtensions dboExtensions(EmbeddedRuntime runtime,
            org.springframework.beans.factory.ObjectProvider<
                    cloud.jengu.dbo.tenant.api.TenantLifecycleListener> listeners,
            org.springframework.beans.factory.ObjectProvider<
                    cloud.jengu.dbo.tenant.api.TenantObserver> observers) {
        return new DboExtensions(runtime, listeners.orderedStream().toList(),
                observers.orderedStream().toList());
    }

    /** The vocabulary an application reads this deployment through. */
    @Bean
    @ConditionalOnMissingBean
    public DboTenants dboTenants(EmbeddedRuntime runtime) {
        return new DboTenants(runtime);
    }

    /**
     * The same refusal the serving distribution makes, at the same point.
     *
     * <p>Its launcher exits 78 rather than serve tenants without a working
     * authority. An embedding that served them quietly would be a second
     * artifact with a different rule under one name, so this refuses the
     * context — and says the same sentence, so that somebody who has met one
     * of them recognises the other.
     */
    private static void refuseToServeWithoutAnAuthority(DboServerProperties properties) {
        boolean hasKek = properties.getAuth().getKek() != null
                && !properties.getAuth().getKek().isBlank();
        if (!hasKek && !properties.getAuth().isDisabled()) {
            throw new IllegalStateException("refusing to serve tenants without an authority. "
                    + "Set dbo.auth.kek (base64, 32 bytes) or explicitly dbo.auth.disabled=true "
                    + "(embedded and test only).");
        }
    }

    /**
     * Where the store's doors go when the application has a web tier.
     *
     * <p>Conditional on the servlet API being present AND on the deployment
     * not having asked for a listener of its own: an application with no web
     * tier, or one migrating, gets the arrangement the serving distribution
     * has and nothing here fires.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnProperty(name = "dbo.mount", havingValue = "servlet", matchIfMissing = true)
    public static class MountedInTheApplicationsWebTier {

        @Bean
        @ConditionalOnMissingBean
        public SpringHttpServer dboSurfaces() {
            return new SpringHttpServer();
        }

        /**
         * Handing the runtime its web tier, the way the runtime takes
         * everything else it does not build itself.
         *
         * <p>A service rather than a constructor argument, because that is
         * how the container already takes a provisioner, a step service, a
         * lane and an observer: the services ARE the configuration. The
         * tenant activator waits for this one, which is what
         * {@code dbo.tenant.http.shared} said it should.
         *
         * <p>It takes the extensions as an argument and does not use them.
         * That is the ordering: the activator brings tenants up once this
         * service arrives, so everything that has to be on the whiteboard
         * before a tenant reaches a point has to be there before this bean.
         */
        @Bean
        public DboRegistrar.Registration dboSurfacesOnTheWhiteboard(EmbeddedRuntime runtime,
                SpringHttpServer surfaces, DboExtensions alreadyRegistered) {
            return runtime.registrar().register(HttpServer.class, surfaces, Map.of());
        }

        /**
         * Every request offered to the surfaces, first.
         *
         * <p>Ordered in front of the application's security chain on purpose:
         * a tenant's doors are guarded by that tenant's own authority, and a
         * second answer to who may read somebody's records is the one thing
         * this store does not share. The reasoning is on the filter.
         */
        @Bean
        public FilterRegistrationBean<DboSurfaceFilter> dboSurfaceFilter(
                SpringHttpServer surfaces) {
            FilterRegistrationBean<DboSurfaceFilter> registered =
                    new FilterRegistrationBean<>(new DboSurfaceFilter(surfaces));
            registered.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
            return registered;
        }
    }
}
