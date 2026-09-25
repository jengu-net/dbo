package cloud.jengu.dbo.spring.test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test of an application built on these assemblies.
 *
 * <p>It is {@link SpringBootTest} with the deployment filled in: a database
 * for this JVM, the world named by {@code dbo.test.world}, a key minted for
 * the run, and — where {@code dbo.test.lane.tenant} names one — a credential
 * that tenant issued and a lane pointing at the port this application came up
 * on.
 *
 * <p><b>A test writes {@code dbo.test.*} and nothing else.</b> Everything the
 * application reads is derived from that, which is what keeps an application's
 * own configuration the shape an integrator copies rather than the shape a
 * test needed.
 *
 * <p><b>Keep {@code dbo.test.*} the same across a module's tests.</b> A
 * context is cached by its configuration, so tests that agree share one — one
 * server, one set of tenants, brought up once. Tests that differ get a SECOND
 * context, and Spring does not close the first: two tenant managers then scan,
 * poll and stream over one database, which the store permits and nothing in a
 * test arbitrates. It shows up as contention rather than as an error.
 *
 * <p><b>The port is chosen before the context, not after it.</b> A worker's
 * lane is built when its bean is, so a port discovered at refresh would be
 * known too late for anything to point at it. This takes one first and tells
 * the server to use it, which is why the web environment is a defined port and
 * not a random one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(initializers = ADeploymentForThisTest.class)
@ExtendWith(TheTenantIsServing.class)
public @interface DboSpringBootTest {
}
