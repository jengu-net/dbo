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
    private static String key;

    private TheDatabaseForThisJvm() {
    }

    /**
     * The key every tenant in this JVM is sealed under.
     *
     * <p>Beside the database because it shares the database's lifetime, and
     * that is the whole reason it is here rather than minted where it is used.
     * A context is cached by its configuration, so a second test class with
     * different settings gets a SECOND context — against the same database,
     * because that is a JVM away and not a context away. A key minted per
     * context would leave the second one holding tenant databases sealed by
     * the first, unable to read a byte of them, and the failure would read as
     * corrupted data rather than as the wrong key.
     *
     * <p>Random per JVM rather than constant, because a key that is the same
     * everywhere is one somebody eventually ships.
     */
    static synchronized String key() {
        if (key == null) {
            byte[] minted = new byte[32];
            new java.security.SecureRandom().nextBytes(minted);
            key = java.util.Base64.getEncoder().encodeToString(minted);
        }
        return key;
    }

    /**
     * A database of its own for the deployment's durable substrate.
     *
     * <p>Beside the tenants rather than among them: the substrate is the
     * deployment's, carrying work between a serving node and the participants
     * inside it, and a tenant's database is one tenant's world. Created once
     * per JVM, like the container it lives in.
     */
    static synchronized String substrateUrl() {
        if (substrate == null) {
            PostgreSQLContainer<?> db = running;
            if (db == null) {
                throw new IllegalStateException("no database is running yet, so there is nowhere "
                        + "to make a substrate \u2014 this is asked for after the container starts");
            }
            try (java.sql.Connection open = java.sql.DriverManager.getConnection(
                    db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    java.sql.Statement make = open.createStatement()) {
                make.execute("CREATE DATABASE " + SUBSTRATE);
            } catch (java.sql.SQLException already) {
                // Made by an earlier context in this JVM, which is the ordinary
                // case: contexts are cached per configuration and this is not.
                if (!"42P04".equals(already.getSQLState())) {
                    throw new IllegalStateException("the substrate database could not be made: "
                            + already.getMessage(), already);
                }
            }
            substrate = db.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + SUBSTRATE + "$1");
        }
        return substrate;
    }

    private static final String SUBSTRATE = "dbo_substrate";

    private static String substrate;

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
