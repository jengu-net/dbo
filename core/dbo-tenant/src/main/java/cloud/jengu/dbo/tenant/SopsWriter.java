package cloud.jengu.dbo.tenant;

import java.time.Instant;
import java.util.Map;

/**
 * Writes a tenant's secrets into a file of its repository as SOPS would, for
 * whatever takes a secret from a person — a screen where somebody types a
 * connection's password — to commit.
 *
 * <p><b>Readable by three, and nobody else.</b> The store opens it through the
 * tenant's Transit key, the owner opens it with their own SOPS and age key,
 * and whoever holds only the repository opens nothing. The writer wraps the
 * data key through Transit's encrypt endpoint alone, so a writer whose token
 * may only encrypt under the tenant's key writes a file it could not read.
 *
 * <p>Whole files, never edits: what is written is everything the file will
 * hold, which is enough for a file holding what one writer owns — one
 * connection's secrets — and never needs the others opened.
 */
public final class SopsWriter {

    private final VaultTransit transit;
    private final String key;

    /**
     * @param transit the Vault the data key is wrapped through, under a token
     *                that may encrypt with the tenant's key
     * @param key     the tenant's Transit key
     */
    public SopsWriter(VaultTransit transit, String key) {
        this.transit = transit;
        this.key = key;
    }

    /**
     * The whole file, encrypted to the recipients the repository's
     * {@code .sops.yaml} names for its path, or the tenant's key alone.
     *
     * @param path     where the file goes in the repository; it ends .sops.json
     * @param secrets  each name and its value
     * @param sopsYaml the repository's {@code .sops.yaml}, or null for none
     * @throws IllegalArgumentException when the path is not a file of secrets,
     *                                  or a rule names what cannot be wrapped
     */
    public byte[] write(String path, Map<String, String> secrets, String sopsYaml) {
        if (path == null || !path.endsWith(OwnersConfiguration.SECRETS_SUFFIX)) {
            throw new IllegalArgumentException("'" + path + "' is not a file of secrets: its "
                    + "name ends " + OwnersConfiguration.SECRETS_SUFFIX);
        }
        SopsRules.Recipients recipients = SopsRules.forPath(sopsYaml, path, transit.address(),
                transit.mount(), key);
        return SopsFile.write(secrets, recipients, dataKey -> transit.wrap(key, dataKey),
                Instant.now());
    }
}
