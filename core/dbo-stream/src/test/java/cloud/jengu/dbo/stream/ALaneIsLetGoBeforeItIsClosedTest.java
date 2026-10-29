package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.framework.ServiceRegistration;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A host stopping, as the framework stops it.
 *
 * <p>The framework stops a bundle before it unregisters what the bundle
 * registered, and this bundle closed each lane's substrate connection when it
 * was stopped. So a runner told afterwards that the lane was gone withdrew over
 * a closed connection, every time, and the far side kept what it had declared.
 */
class ALaneIsLetGoBeforeItIsClosedTest {

    @Test
    @DisplayName("a host stopping takes each lane out of the registry before it closes it")
    @Proving(DboPromises.PROC_A_HOST_STOPPING_WITHDRAWS_WHILE_ITS_LANES_STILL_CARRY)
    void aLaneIsLetGoBeforeItIsClosed() {
        List<String> said = new ArrayList<>();
        List<ServiceRegistration<?>> registered = new ArrayList<>(List.of(
                registration("north", said), registration("south", said)));
        List<AutoCloseable> held = new ArrayList<>(List.of(
                () -> said.add("closed north"), () -> said.add("closed south")));

        Activator.letGo(registered, held);

        assertEquals(List.of("unregistered north", "unregistered south", "closed north",
                "closed south"), said);
        assertEquals(List.of(), registered);
        assertEquals(List.of(), held);
    }

    private static ServiceRegistration<?> registration(String lane, List<String> said) {
        return (ServiceRegistration<?>) Proxy.newProxyInstance(
                ServiceRegistration.class.getClassLoader(),
                new Class<?>[] {ServiceRegistration.class}, (proxy, method, arguments) -> {
                    if ("unregister".equals(method.getName())) {
                        said.add("unregistered " + lane);
                    }
                    return null;
                });
    }
}
