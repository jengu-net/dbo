package cloud.jengu.dbo.auth;

import java.util.List;
import java.util.Optional;

/**
 * The page a tenant's people sign in on.
 *
 * <p>The store hands over everything the page carries already built and
 * escaped: where the password form posts, the fields that carry the
 * application's request through it, and where each broker's button goes. A
 * page decides how those look and never what they are. A page that rebuilt
 * the hidden fields itself would get them wrong in the same places every
 * host gets them wrong, and a button whose target the page chose could send
 * somebody to a broker the tenant does not accept.
 *
 * <p>One page answers for the deployment and is told which tenant it is
 * drawing. Answering empty leaves that tenant on the store's own page.
 */
public interface LoginPage {

    /** The page, or empty for the store's own. */
    Optional<String> render(Form form);

    /**
     * One sign-in attempt at one tenant.
     *
     * @param error     why the last attempt on this page failed, an OAuth
     *                  error code
     * @param password  the password form, empty where nobody at this tenant
     *                  holds a password
     * @param providers the brokers this tenant accepts, contracted one first
     * @param pin       the PIN form, empty where people do not sign in with one —
     *                  everywhere but a site serving a place of its tenant
     */
    record Form(String tenant, Optional<String> error, Optional<Password> password,
            List<Provider> providers, Optional<Pin> pin) {

        /** A page where nobody signs in with a PIN. */
        public Form(String tenant, Optional<String> error, Optional<Password> password,
                List<Provider> providers) {
            this(tenant, error, password, providers, Optional.empty());
        }
    }

    /** Post {@code login} and {@code pin} to {@code action} with these fields. */
    record Pin(String action, String hiddenFields) {}

    /** Post {@code login} and {@code password} to {@code action} with these fields. */
    record Password(String action, String hiddenFields) {}

    /** A broker, and where its button goes. */
    record Provider(String broker, String href) {}

    /** The store's own page. */
    LoginPage BARE = form -> {
        StringBuilder page = new StringBuilder("<!doctype html><html><body>");
        form.error().ifPresent(error -> page.append("<p>").append(escaped(error)).append("</p>"));
        for (Provider provider : form.providers()) {
            page.append("<p><a href=\"").append(provider.href()).append("\">")
                    .append(escaped(provider.broker())).append("</a></p>");
        }
        form.password().ifPresent(password -> page
                .append("<form method=\"post\" action=\"").append(password.action()).append("\">")
                .append(password.hiddenFields())
                .append("<input name=\"login\" autocomplete=\"username\">")
                .append("<input name=\"password\" type=\"password\""
                        + " autocomplete=\"current-password\">")
                .append("<button type=\"submit\">Sign in</button></form>"));
        form.pin().ifPresent(pin -> page
                .append("<form method=\"post\" action=\"").append(pin.action()).append("\">")
                .append(pin.hiddenFields())
                .append("<input name=\"login\" autocomplete=\"username\">")
                .append("<input name=\"pin\" type=\"password\" inputmode=\"numeric\""
                        + " autocomplete=\"one-time-code\">")
                .append("<button type=\"submit\">Sign in with PIN</button></form>"));
        return Optional.of(page.append("</body></html>").toString());
    };

    /** Text made safe for an HTML attribute or element. */
    static String escaped(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
