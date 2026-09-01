package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.Session;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assume the identity you already hold, so that what you do carries your name
 *.
 *
 * <p>Through the tenant's own authority, by the same request an HTTP caller
 * makes: a refusal here is the authority's refusal, arrived at by the code that
 * refuses everybody else. A console that checked permissions itself would be a
 * second answer, and second answers drift.
 *
 * <p><b>No secret on a command line.</b> A console logs its commands and keeps
 * a history, so a secret passed as an argument is a credential in a file. It is
 * prompted for and masked, or a pre-issued token is read from a path.
 *
 * <p>Nothing prints the token. What it prints is who you now are and until
 * when.
 */
@Command(scope = "dbo", name = "login",
        description = "Signs in to a tenant's authority; the secret is prompted for, never typed as an argument.")
@Service
public class LoginCommand implements Action {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EXPIRES_IN = Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)");

    @Option(name = "--tenant", description = "Which authority. Defaults to where you are standing.")
    @Completion(TenantCompleter.class)
    private String tenant;

    @Option(name = "--client", description = "The client id to sign in as.")
    private String client;

    @Option(name = "--token-file",
            description = "A pre-issued token, read from this path rather than obtained here.")
    private String tokenFile;

    @Reference
    private Session session;

    @Override
    public Object execute() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        String code = tenant != null ? tenant : ConsoleSession.tenant(session);
        if (code == null) {
            System.out.println("Which tenant's authority? dbo:context <tenant> first, or"
                    + " dbo:login --tenant <tenant>.");
            return null;
        }
        if (!Tenants.codes(context).contains(code)) {
            System.out.println("This node is not serving '" + code + "', so it has no authority"
                    + " to sign in to here.");
            return null;
        }
        String token = tokenFile != null ? fromFile() : obtained(context, code);
        if (token == null) {
            return null;
        }
        if (tokenFile != null && !belongsTo(token, Tenants.oidcBase(context, code))) {
            // Structure, not authority: whether this token is accepted is the
            // tenant's decision at the moment it is used. What is checked here
            // is that it was issued for THIS tenant and has not expired — a
            // session standing in one tenant holding another's token would be
            // an identity nobody can act with and a prompt that lies.
            System.out.println("That token was not issued by " + code + ", or it has expired.");
            return null;
        }
        String actor = subjectOf(token, code);
        Instant expires = expiryOf(token);
        ConsoleSession.signedIn(session, code, actor, token, expires);
        System.out.println("Signed in to " + code + " as " + actor
                + (expires == null ? "." : ", until " + expires + "."));
        return null;
    }

    /** A token somebody already issued — read, never echoed. */
    private String fromFile() throws IOException {
        Path path = Path.of(tokenFile);
        if (!Files.isReadable(path)) {
            System.out.println("No readable token at " + path + ".");
            return null;
        }
        String token = Files.readString(path).trim();
        if (token.isEmpty()) {
            System.out.println("The file at " + path + " is empty.");
            return null;
        }
        return token;
    }

    /**
     * The client-credentials request, made the way anything else makes it.
     *
     * <p>The secret is read from the terminal with a mask, so it is in no
     * command line and no history. It is never held after the request.
     */
    private String obtained(BundleContext context, String code) throws Exception {
        String clientId = client;
        if (clientId == null) {
            clientId = session.readLine("client id: ", null);
        }
        if (clientId == null || clientId.isBlank()) {
            System.out.println("No client id, so nothing to sign in as.");
            return null;
        }
        String secret = session.readLine("secret: ", '*');
        if (secret == null || secret.isBlank()) {
            System.out.println("No secret, so nothing to prove.");
            return null;
        }
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        HttpResponse<String> answer = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(Tenants.oidcBase(context, code) + "/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (answer.statusCode() != 200) {
            // The authority's own words. A friendlier sentence here would be
            // this command's opinion about somebody else's decision.
            System.out.println("The authority refused (" + answer.statusCode() + "): "
                    + answer.body());
            return null;
        }
        Matcher token = ACCESS_TOKEN.matcher(answer.body());
        if (!token.find()) {
            System.out.println("The authority answered 200 with no access token in it.");
            return null;
        }
        Matcher expiresIn = EXPIRES_IN.matcher(answer.body());
        if (expiresIn.find()) {
            session.put("dbo.context.expires",
                    Instant.now().plusSeconds(Long.parseLong(expiresIn.group(1))).toString());
        }
        return token.group(1);
    }

    /** Whether the token names this tenant's issuer and is still in date. */
    private static boolean belongsTo(String token, String issuer) {
        Instant expires = expiryOf(token);
        if (expires != null && expires.isBefore(Instant.now())) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            String claims = new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8);
            Matcher found = Pattern.compile("\"iss\"\\s*:\\s*\"([^\"]+)\"").matcher(claims);
            return found.find() && found.group(1).equals(issuer);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Who the token says you are, read from its own claims rather than assumed. */
    private static String subjectOf(String token, String fallback) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return fallback;
        }
        try {
            String claims = new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8);
            for (String claim : new String[] {"sub", "client_id", "azp"}) {
                Matcher found = Pattern.compile("\"" + claim + "\"\\s*:\\s*\"([^\"]+)\"")
                        .matcher(claims);
                if (found.find()) {
                    return found.group(1);
                }
            }
        } catch (RuntimeException unreadable) {
            return fallback;
        }
        return fallback;
    }

    /** When it stops being valid, from the token rather than from a guess. */
    private static Instant expiryOf(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            String claims = new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8);
            Matcher exp = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)").matcher(claims);
            return exp.find() ? Instant.ofEpochSecond(Long.parseLong(exp.group(1))) : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
