package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.console.Session;

import java.time.Instant;

/**
 * Where the console keeps who you are and where you are standing (#76).
 *
 * <p>Two honest reasons and no third. <b>Provenance:</b> signing in is assuming
 * an identity you already possess, so that what you do carries your name —
 * {@code git config user.email}, not a lock. <b>Fidelity:</b> acting through
 * the same authority path as every other caller means a permissions defect
 * reproduces in the console instead of being routed around by it.
 *
 * <p><b>The console is a tool, not a boundary.</b> Whoever runs it already
 * holds the tenant's database credentials; nothing here keeps anybody out, and
 * reading it as a control would be reading it wrong.
 *
 * <p>In the session rather than in a file: it dies with the session, it is
 * never written anywhere, and a second console window is a second identity
 * rather than a shared one.
 */
final class ConsoleSession {

    private static final String TENANT = "dbo.context.tenant";
    private static final String ACTOR = "dbo.context.actor";
    private static final String TOKEN = "dbo.context.token";
    private static final String EXPIRES = "dbo.context.expires";

    private ConsoleSession() {
    }

    static String tenant(Session session) {
        Object code = session.get(TENANT);
        return code == null ? null : String.valueOf(code);
    }

    /**
     * The tenant a command should act on: what it was told, or where the
     * console is standing.
     *
     * <p>A flag always wins over a position — somebody who names a tenant means
     * that tenant, and silently preferring the context is how a command lies
     * about which one it read.
     */
    static String tenantOr(Session session, String named) {
        return named != null ? named : tenant(session);
    }

    static void tenant(Session session, String code) {
        session.put(TENANT, code);
        prompt(session);
    }

    static String actor(Session session) {
        return expired(session) ? null : string(session, ACTOR);
    }

    /**
     * The token, or null when there is none or it has expired.
     *
     * <p>Never printed and never returned to a command that only wants to know
     * whether somebody is signed in — a console logs what it prints.
     */
    static String token(Session session) {
        return expired(session) ? null : string(session, TOKEN);
    }

    static void signedIn(Session session, String tenant, String actor, String token,
            Instant expires) {
        session.put(TENANT, tenant);
        session.put(ACTOR, actor);
        session.put(TOKEN, token);
        session.put(EXPIRES, expires == null ? null : expires.toString());
        prompt(session);
    }

    static void signedOut(Session session) {
        session.put(ACTOR, null);
        session.put(TOKEN, null);
        session.put(EXPIRES, null);
        prompt(session);
    }

    static Instant expires(Session session) {
        String at = string(session, EXPIRES);
        return at == null ? null : Instant.parse(at);
    }

    /**
     * Whether the session has aged out.
     *
     * <p>Checked on every read rather than swept: an identity that has expired
     * and still shows in the prompt is the one thing worse than no identity.
     */
    private static boolean expired(Session session) {
        String at = string(session, EXPIRES);
        if (at == null) {
            return string(session, TOKEN) == null;
        }
        if (Instant.parse(at).isAfter(Instant.now())) {
            return false;
        }
        session.put(ACTOR, null);
        session.put(TOKEN, null);
        session.put(EXPIRES, null);
        prompt(session);
        return true;
    }

    private static String string(Session session, String key) {
        Object value = session.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Both halves in the prompt, because a view that silently belongs to
     * somebody else is worse than no view.
     */
    static void prompt(Session session) {
        String tenant = tenant(session);
        String actor = string(session, ACTOR);
        if (tenant == null && actor == null) {
            session.put("PROMPT", null);
            return;
        }
        session.put("PROMPT", "karaf@root [" + (tenant == null ? "no tenant" : tenant)
                + (actor == null ? "" : " as " + actor) + "]> ");
    }
}
