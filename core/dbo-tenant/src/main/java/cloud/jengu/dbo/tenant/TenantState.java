package cloud.jengu.dbo.tenant;

/**
 * What a runtime is doing about one tenant it has been told about.
 *
 * <p>A runtime is the only party that knows this. A spec directory says what
 * was <b>declared</b>; only the runtime knows that a spec was written and its
 * tenant never came up, or came up and later stopped — which is the whole
 * point of asking it rather than listing the files.
 */
public record TenantState(String code, State state) {

    public enum State {
        /** Answering: the endpoint is up and the engine is wired. */
        SERVING,
        /**
         * Declared, and not answering yet for a reason that resolves itself —
         * a dependency whose upstream is not up, or a scan that has not
         * reached it. The next round is where it changes.
         */
        COMING_UP,
        /**
         * Declared, and bring-up refused. The state an operator most wants,
         * and the one a bare list of served tenants silently omits.
         */
        FAILED;

        /** Lowercase, because it crosses a wire as a word rather than a Java name. */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }
}
