package cloud.jengu.dbo.samples.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * An application that serves FHIR tenants because it added one dependency.
 *
 * <p>There is nothing here about a container. No bundle is named, no framework
 * is started, no port is opened for the store: the application's own port
 * serves it, through the application's own filter chain, and a tenant's doors
 * appear under {@code /t/<code>/fhir}. What makes that true is
 * {@code dbo-spring-boot-server} being on the classpath and the configuration
 * in {@code application.yaml} — which is the whole of the integration.
 *
 * <p><b>The tenants are files.</b> {@code dbo.tenants.directory} names a
 * directory this application watches, and a spec appearing there is a tenant
 * coming up. They are configuration rather than code for the reason every
 * deployment wants: a tenant is added by writing a file, not by a release.
 */
@SpringBootApplication
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
