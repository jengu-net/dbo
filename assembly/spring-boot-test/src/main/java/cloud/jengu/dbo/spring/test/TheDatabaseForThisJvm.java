package cloud.jengu.dbo.spring.test;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One database for every test in this JVM.
 *
 * <p>A container per test class is most of a suite's wall clock, and the
 * isolation it buys is already had a cheaper way: this store keeps a database
 * per tenant, so two test classes on one server are as separate as two
 * deployments — provided they do not declare the same tenant code, because a
 * tenant's database name is derived from it.
 *
 * <p><b>Never stopped.</b> It is reaped when the JVM exits. Stopping it from
 * any one class's teardown would pull the floor out from under the classes
 * still running, and a suite that fails that way blames whichever class was
 * unlucky rather than the one that stopped it.
 *
 * <p>The image is read once, from the first test that asks. A JVM running two
 * tests that want different databases is a suite split across two Gradle
 * tasks, which is a decision somebody makes rather than one this discovers.
 */
final class TheDatabaseForThisJvm {

    private static PostgreSQLContainer<?> running;
    private static String startedFor;

    private TheDatabaseForThisJvm() {
    }

    static synchronized PostgreSQLContainer<?> get(String image) {
        if (running == null) {
            running = new PostgreSQLContainer<>(image);
            running.start();
            startedFor = image;
            return running;
        }
        if (!startedFor.equals(image)) {
            throw new IllegalStateException("this JVM already runs " + startedFor + " and a test "
                    + "in it asks for " + image + ". One database serves every test in a JVM, so "
                    + "two images means two Gradle test tasks rather than one — which is a "
                    + "decision to make in the build rather than one to discover here.");
        }
        return running;
    }
}
