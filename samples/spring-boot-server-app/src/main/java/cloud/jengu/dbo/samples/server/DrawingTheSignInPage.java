package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.auth.LoginPage;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The page the hospital's people sign in on.
 *
 * <p>The application draws the hospital's page and leaves every other
 * tenant on the store's own. What the page shows is the hospital's; what it
 * posts and where each button goes is the store's, handed over already
 * escaped, and it is put on the page as it came.
 */
@Component
public final class DrawingTheSignInPage implements LoginPage {

    /** The tenant this application draws a page for. */
    public static final String HOSPITAL = "hogwarts";

    @Override
    public Optional<String> render(Form form) {
        if (!HOSPITAL.equals(form.tenant())) {
            return Optional.empty();
        }
        StringBuilder page = new StringBuilder("<!doctype html><html lang=\"en\"><body>")
                .append("<h1>Hogwarts Infirmary</h1>");
        form.error().ifPresent(error ->
                page.append("<p role=\"alert\">That login and password did not match.</p>"));
        for (Provider provider : form.providers()) {
            page.append("<a class=\"broker\" href=\"").append(provider.href()).append("\">")
                    .append("Sign in with ").append(LoginPage.escaped(provider.broker()))
                    .append("</a>");
        }
        form.password().ifPresent(password -> page
                .append("<form method=\"post\" action=\"").append(password.action()).append("\">")
                .append(password.hiddenFields())
                .append("<input name=\"login\"><input name=\"password\" type=\"password\">")
                .append("<button>Sign in</button></form>"));
        return Optional.of(page.append("</body></html>").toString());
    }
}
