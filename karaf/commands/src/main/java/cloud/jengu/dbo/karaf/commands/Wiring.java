package cloud.jengu.dbo.karaf.commands;

/**
 * Whether the dbo packages are wired into this bundle yet.
 *
 * <p>They arrive with the bundle set, which {@code dbo-console:up} installs —
 * and this bundle is installed from {@code deploy/} before it, so at startup
 * they are legitimately absent and the imports are optional.
 *
 * <p><b>Nothing here names a dbo type, and that is the point.</b> Karaf
 * instantiates every {@code @Service} action by reflection and calls
 * {@code getDeclaredMethods()} on it, which throws {@code NoClassDefFoundError}
 * if <em>any</em> declared method signature names a class that is not wired —
 * and the extender then registers <b>no command from this bundle at all</b>.
 * A guard inside a method body is too late; the signature is what is read. So
 * the dbo-typed work lives in {@link RunView}, which nothing reflects on, and
 * the commands hold strings.
 */
final class Wiring {

    private Wiring() {
    }

    static boolean available() {
        try {
            Class.forName("cloud.jengu.dbo.work.Runs", false, Wiring.class.getClassLoader());
            return true;
        } catch (Throwable notHereYet) {
            return false;
        }
    }

    /** What to say when they are not. */
    static void explainAbsence() {
        System.out.println("The dbo bundles are not wired into the console yet, so there is"
                + " nowhere to read runs from. Run dbo-console:up — it installs the set and"
                + " re-reads this bundle against it.");
    }
}
