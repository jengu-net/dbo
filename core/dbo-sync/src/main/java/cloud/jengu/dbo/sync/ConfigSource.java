package cloud.jengu.dbo.sync;

import java.util.List;

/**
 * Where declared configuration is read from, and what that read is called.
 *
 * <p>A git repository, a mounted ConfigMap, a directory, a lane from a cloud:
 * what differs between them is reading, and reading is all this is. What a
 * declaration means, where it lands and what it costs to apply belong to
 * {@link ConfigApplication} and to whoever applies — which is what keeps four
 * sources from becoming four designs, each with its own idea of what a partial
 * read means.
 *
 * <p><b>The marker is how a source says nothing has changed.</b> Applying is
 * idempotent, so re-applying an unchanged set is correct; it is just work
 * nobody asked for, on every pass, for as long as the deployment runs — and in
 * a store whose feed everything downstream is watching, a rewrite is not free
 * even when the bytes are identical.
 *
 * <p>The source remembers nothing itself. What a scope last agreed with is on
 * the run, in the store, where it survives a restart and answers somebody
 * asking what this tenant is configured from. A marker kept in a field would
 * make the first pass after every restart a full re-application, and would
 * answer that question for nobody.
 */
public interface ConfigSource {

    /**
     * One read.
     *
     * @param declarations everything the source declares, changed or not — a
     *                     caller that must re-apply, because the last pass left
     *                     a card, needs the whole set rather than a difference
     * @param marker       what the source calls this read: a commit, a digest,
     *                     a generation. Recorded on the run, compared with the
     *                     one before it, and never interpreted
     */
    record Fetch(List<ConfigApplication.Declared> declarations, String marker) {

        public Fetch {
            declarations = List.copyOf(declarations);
        }
    }

    /**
     * Reads the source.
     *
     * <p><b>A source that cannot be read throws.</b> It never answers with an
     * empty set, because empty and unreachable are the same sentence to
     * whoever has to decide what is missing — and deciding that wrongly is how
     * a bad read becomes a withdrawal.
     */
    Fetch fetch();

    /**
     * A marker for a set that has none of its own: the content, digested.
     *
     * <p>For a source whose declarations are their own version — a directory,
     * a face's built-in vocabulary. A git repository has a better answer and
     * should give it, because a commit is what a person would name.
     */
    static String markerOf(List<ConfigApplication.Declared> declarations) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            for (ConfigApplication.Declared declared : declarations) {
                digest.update(declared.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(declared.payload());
            }
            return java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException never) {
            throw new IllegalStateException("SHA-256 is not optional in a JRE", never);
        }
    }
}
