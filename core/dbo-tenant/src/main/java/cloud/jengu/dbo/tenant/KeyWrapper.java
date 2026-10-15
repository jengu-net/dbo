package cloud.jengu.dbo.tenant;

import java.nio.charset.StandardCharsets;

/**
 * What wraps a tenant's working key: the deployment's own key, or a Transit
 * key — in the deployment's Vault, or in one the tenant's owner runs.
 *
 * <p>One port, because every key service there is wraps and unwraps the same
 * way, and what differs between them is who holds the key and who may stop
 * the tenant by taking it away.
 */
interface KeyWrapper {

    byte[] wrap(byte[] key);

    /** @throws RuntimeException when it will not, saying why */
    byte[] unwrap(byte[] wrapped);

    /** The same key wrapped under the wrapping key's latest version, or as it was. */
    default byte[] rewrapped(byte[] wrapped) {
        return wrapped;
    }

    /** Which key wraps, as the record of the working key names it. */
    String describe();

    /**
     * Whether it is held outside this deployment's configuration, and so can
     * be taken away while the tenant serves: what is checked again on a timer.
     */
    boolean heldElsewhere();

    /** The deployment's own key: what every tenant's keys were wrapped by. */
    static KeyWrapper deployment(byte[] kek) {
        cloud.jengu.dbo.auth.KeyProtector protector = new cloud.jengu.dbo.auth.KeyProtector(kek);
        return new KeyWrapper() {
            @Override
            public byte[] wrap(byte[] key) {
                return protector.wrap(key);
            }

            @Override
            public byte[] unwrap(byte[] wrapped) {
                return protector.unwrap(wrapped);
            }

            @Override
            public String describe() {
                return "deployment";
            }

            @Override
            public boolean heldElsewhere() {
                return false;
            }
        };
    }

    /** A Transit key: Vault wraps, unwraps and rewraps, and the key never leaves it. */
    static KeyWrapper transit(VaultTransit vault, String key) {
        return new KeyWrapper() {
            @Override
            public byte[] wrap(byte[] working) {
                return vault.wrap(key, working).getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public byte[] unwrap(byte[] wrapped) {
                return vault.unwrap(key, new String(wrapped, StandardCharsets.UTF_8));
            }

            @Override
            public byte[] rewrapped(byte[] wrapped) {
                return vault.rewrap(key, new String(wrapped, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String describe() {
                return "transit " + vault.address() + " " + vault.mount() + " " + key;
            }

            @Override
            public boolean heldElsewhere() {
                return true;
            }
        };
    }
}
