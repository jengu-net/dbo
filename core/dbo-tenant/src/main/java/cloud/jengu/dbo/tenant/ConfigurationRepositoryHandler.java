package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.tenant.git.StoredRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;

import cloud.jengu.dbo.sync.ConfigApplication;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * The tenant's configuration repository, served over git's smart HTTP.
 *
 * <p>An owner clones it, fetches it and pushes to it with an ordinary git
 * client at the tenant's own address, and nothing outside the store has to be
 * reached for the tenant to have its configuration: the repository is kept in
 * the tenant's database, under the tenant's authority.
 *
 * <p><b>Nothing is anonymous.</b> A git client signs in with Basic
 * credentials, a client the tenant's authority issued and its secret, and a
 * caller already holding a token may present it. Reading is {@value #READ};
 * pushing a branch is {@code config/push:<branch>}, or {@value #PUSH_ANY} for
 * every branch, so who may push the branch the tenant applies is decided apart
 * from who may push their own. There is no list of who may do what kept in the
 * repository itself, where a push could change it.
 *
 * <p>Only branches are kept. A tag or any other ref is refused by name.
 *
 * <p><b>The branch the tenant applies is checked before it moves.</b> A push
 * to it is previewed at the pushed commit, as asking for a preview would, and
 * refused with what refused it where the commit would not apply, so a commit
 * that cannot apply is never on the branch. Once it lands it is applied, as one
 * recorded run under whoever pushed, and what the apply came to is told back
 * to the pusher on the push itself. The branch is not deleted while the tenant
 * applies it.
 */
final class ConfigurationRepositoryHandler implements HttpHandler {

    /** What reading the repository takes: a clone and a fetch. */
    static final String READ = cloud.jengu.dbo.auth.Scopes.CONFIG_READ;

    /** What pushing to one branch takes, followed by its name. */
    static final String PUSH = cloud.jengu.dbo.auth.Scopes.CONFIG_PUSH;

    /** What pushing to every branch takes. */
    static final String PUSH_ANY = PUSH + "*";

    /** A push larger than this is not somebody's configuration. */
    static final long LARGEST_PUSH = 256L * 1024 * 1024;

    private static final String UPLOAD = "git-upload-pack";
    private static final String RECEIVE = "git-receive-pack";

    private final TenantAuthority authority;
    private final DataSource ds;
    private final String code;
    private final java.util.function.Supplier<ConfigurationHandler.Owners> owners;
    private final java.util.function.Supplier<String> declared;

    /**
     * @param owners   the tenant's apply of its owner's configuration, or null
     *                 while the tenant does not take one, when it has no
     *                 repository to serve
     * @param declared the ref the tenant's declaration applies
     */
    ConfigurationRepositoryHandler(TenantAuthority authority, DataSource ds, String code,
            java.util.function.Supplier<ConfigurationHandler.Owners> owners,
            java.util.function.Supplier<String> declared) {
        this.authority = authority;
        this.ds = ds;
        this.code = code;
        this.owners = owners;
        this.declared = declared;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath()
                    .substring(exchange.getHttpContext().getPath().length());
            String query = exchange.getRequestURI().getRawQuery();
            String service;
            boolean advertising;
            if ("/info/refs".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                service = query == null ? null : switch (query) {
                    case "service=" + UPLOAD -> UPLOAD;
                    case "service=" + RECEIVE -> RECEIVE;
                    default -> null;
                };
                advertising = true;
            } else if (("/" + UPLOAD).equals(path) || ("/" + RECEIVE).equals(path)) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    plain(exchange, 405, "git asks this with POST");
                    return;
                }
                service = path.substring(1);
                advertising = false;
            } else {
                plain(exchange, 404, "this is the tenant's configuration repository, which "
                        + "git reads over smart HTTP only");
                return;
            }
            if (service == null) {
                plain(exchange, 403, "only git's smart HTTP is served here");
                return;
            }
            ConfigurationHandler.Owners applying = owners.get();
            if (applying == null) {
                plain(exchange, 404, code + " does not take its owner's configuration from a "
                        + "repository; its declaration says so with a configuration block");
                return;
            }
            Optional<TenantAuthority.AuthContext> asking = signedIn(exchange);
            if (asking.isEmpty()) {
                exchange.getResponseHeaders().set("WWW-Authenticate",
                        "Basic realm=\"" + code + " configuration\"");
                plain(exchange, 401, "sign in with a client the tenant issued and its secret");
                return;
            }
            List<String> scopes = asking.get().scopes();
            if (UPLOAD.equals(service) && !scopes.contains(READ)) {
                plain(exchange, 403, "reading the configuration takes " + READ);
                return;
            }
            if (RECEIVE.equals(service) && scopes.stream().noneMatch(s -> s.startsWith(PUSH))) {
                plain(exchange, 403, "pushing takes " + PUSH + "<branch> or " + PUSH_ANY);
                return;
            }
            try (StoredRepository repository = StoredRepository.open(ds, code)) {
                if (UPLOAD.equals(service)) {
                    upload(exchange, repository, advertising);
                } else {
                    receive(exchange, repository, advertising, scopes, applying,
                            asking.get().clientId());
                }
            }
        } catch (RuntimeException failed) {
            org.slf4j.LoggerFactory.getLogger(ConfigurationRepositoryHandler.class).error(
                    "the configuration repository could not answer: tenant={}", code, failed);
            plain(exchange, 500, "the configuration repository could not answer");
        } finally {
            exchange.close();
        }
    }

    private void upload(HttpExchange exchange, StoredRepository repository, boolean advertising)
            throws IOException {
        UploadPack upload = new UploadPack(repository);
        upload.setBiDirectionalPipe(false);
        String protocol = exchange.getRequestHeaders().getFirst("Git-Protocol");
        if (protocol != null) {
            upload.setExtraParameters(List.of(protocol.split(":")));
        }
        if (advertising) {
            ByteArrayOutputStream advertised = new ByteArrayOutputStream();
            upload.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(
                    new PacketLineOut(advertised)), UPLOAD);
            send(exchange, "application/x-" + UPLOAD + "-advertisement",
                    advertised.toByteArray());
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/x-" + UPLOAD + "-result");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (InputStream in = body(exchange); OutputStream out = exchange.getResponseBody()) {
            upload.upload(in, out, null);
        }
    }

    private void receive(HttpExchange exchange, StoredRepository repository,
            boolean advertising, List<String> scopes, ConfigurationHandler.Owners applying,
            String by) throws IOException {
        ReceivePack receive = new ReceivePack(repository);
        receive.setBiDirectionalPipe(false);
        receive.setCheckReceivedObjects(true);
        receive.setMaxPackSizeLimit(LARGEST_PUSH);
        String applied = appliedBranch(repository);
        receive.setPreReceiveHook((pack, commands) -> {
            for (ReceiveCommand command : commands) {
                String refused = refused(command.getRefName(), scopes);
                if (refused == null && command.getRefName().equals(applied)) {
                    refused = command.getType() == ReceiveCommand.Type.DELETE
                            ? code + " applies " + shortName(applied) + ", which is not "
                                    + "deleted while it does"
                            : wouldNotApply(applying, command, by);
                }
                if (refused != null) {
                    command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, refused);
                }
            }
        });
        receive.setPostReceiveHook((pack, commands) -> {
            for (ReceiveCommand command : commands) {
                if (command.getRefName().equals(applied)
                        && command.getResult() == ReceiveCommand.Result.OK
                        && command.getType() != ReceiveCommand.Type.DELETE) {
                    said(pack, applying, shortName(applied), by);
                }
            }
        });
        if (advertising) {
            ByteArrayOutputStream advertised = new ByteArrayOutputStream();
            PacketLineOut out = new PacketLineOut(advertised);
            out.writeString("# service=" + RECEIVE + "\n");
            out.end();
            receive.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(out));
            send(exchange, "application/x-" + RECEIVE + "-advertisement",
                    advertised.toByteArray());
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/x-" + RECEIVE + "-result");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (InputStream in = body(exchange); OutputStream out = exchange.getResponseBody()) {
            receive.receive(in, out, null);
        }
    }

    /**
     * The branch the tenant applies, as a ref: the one its declaration names,
     * or the one HEAD names where it names HEAD; null where it names a tag or
     * a commit, which no push moves.
     */
    private String appliedBranch(StoredRepository repository) throws IOException {
        String ref = declared.get();
        if (ref == null || Constants.HEAD.equals(ref)) {
            org.eclipse.jgit.lib.Ref head = repository.exactRef(Constants.HEAD);
            return head != null && head.isSymbolic() ? head.getTarget().getName() : null;
        }
        if (ref.startsWith(Constants.R_REFS)) {
            return ref.startsWith(Constants.R_HEADS) ? ref : null;
        }
        if (org.eclipse.jgit.lib.ObjectId.isId(ref)
                || repository.exactRef(Constants.R_TAGS + ref) != null) {
            return null;
        }
        return Constants.R_HEADS + ref;
    }

    private static String shortName(String ref) {
        return ref.substring(Constants.R_HEADS.length());
    }

    /** Why the commit pushed would not apply, as the preview says it, or null where it would. */
    private static String wouldNotApply(ConfigurationHandler.Owners applying,
            ReceiveCommand command, String by) {
        try {
            applying.apply(command.getNewId().name(), by, true);
            return null;
        } catch (IllegalArgumentException unreadable) {
            return "would not apply: " + unreadable.getMessage();
        }
    }

    /** The branch applied, and what that came to told back to whoever pushed. */
    private void said(org.eclipse.jgit.transport.ReceivePack pack,
            ConfigurationHandler.Owners applying, String branch, String by) {
        try {
            OwnersConfiguration.Applied applied = applying.apply(branch, by, false);
            ConfigApplication.Outcome outcome = applied.outcome();
            pack.sendMessage("applied " + branch + " at " + applied.commit().id() + ": read "
                    + outcome.read() + ", applied " + outcome.applied() + ", unchanged "
                    + outcome.unchanged() + ", skipped " + outcome.skipped() + ", withdrawn "
                    + outcome.withdrawn());
            for (ConfigApplication.Card card : outcome.cards()) {
                pack.sendMessage("card " + card.declaration() + ": " + card.reason());
            }
        } catch (RuntimeException failed) {
            // Landed and not applied: the branch moved, so the next apply
            // that is asked for, or the tenant's next start, tries it again.
            pack.sendMessage("not applied: " + failed.getMessage());
            org.slf4j.LoggerFactory.getLogger(ConfigurationRepositoryHandler.class).warn(
                    "a push landed and was not applied: tenant={}", code, failed);
        }
    }

    /** Why a push to this ref is refused for these scopes, or null where it is not. */
    static String refused(String ref, List<String> scopes) {
        if (!ref.startsWith(Constants.R_HEADS)) {
            return "only branches are kept here, and " + ref + " is not one";
        }
        String branch = ref.substring(Constants.R_HEADS.length());
        if (scopes.contains(PUSH_ANY) || scopes.contains(PUSH + branch)) {
            return null;
        }
        return "pushing " + branch + " takes " + PUSH + branch;
    }

    /** The caller, from Basic credentials a git client sends or a token it was given. */
    private Optional<TenantAuthority.AuthContext> signedIn(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return Optional.empty();
        }
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authority.validate(header.substring(7).trim());
        }
        if (!header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return Optional.empty();
        }
        String pair;
        try {
            pair = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        int colon = pair.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        // Through the token the authority would issue the client, so a
        // client this door admits is exactly one the token endpoint would.
        if (authority.token(pair.substring(0, colon), pair.substring(colon + 1), null)
                instanceof TenantAuthority.TokenResult.Issued issued) {
            return authority.validate(issued.accessToken());
        }
        return Optional.empty();
    }

    private static InputStream body(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        return "gzip".equalsIgnoreCase(encoding) || "x-gzip".equalsIgnoreCase(encoding)
                ? new GZIPInputStream(in) : in;
    }

    private static void send(HttpExchange exchange, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void plain(HttpExchange exchange, int status, String message)
            throws IOException {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
