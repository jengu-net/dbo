package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.Session;

/**
 * Puts the identity down and leaves the position.
 *
 * <p>Reads need no identity — somebody who can run the console can run
 * {@code psql} — so this is about what the next action would be attributed to,
 * not about what can be seen.
 */
@Command(scope = "dbo", name = "logout", description = "Drops the console's identity.")
@Service
public class LogoutCommand implements Action {

    @Reference
    private Session session;

    @Override
    public Object execute() {
        if (ConsoleSession.actor(session) == null) {
            System.out.println("Not signed in.");
            return null;
        }
        ConsoleSession.signedOut(session);
        System.out.println("Signed out. Still standing in "
                + ConsoleSession.tenant(session) + "; reads need no identity.");
        return null;
    }
}
