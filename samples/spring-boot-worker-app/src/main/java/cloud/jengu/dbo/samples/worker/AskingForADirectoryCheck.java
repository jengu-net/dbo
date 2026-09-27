package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * This application asking for work it does not perform.
 *
 * <p>Every other class in this sample is a participant as a <b>performer</b>:
 * a bean, an interface, and work arriving over a lane. This is the same
 * participant as an <b>initiator</b> — same application, same credential, same
 * enrolment — asking the hospital to start a run.
 *
 * <p>And the step it asks for is one this application cannot perform.
 * {@code fleet.directory.check} is declared by the deployment in
 * {@code mom.json}, performed by a bean inside the serving application, and
 * answered for every tenant at once. Nothing here knows any of that: it posts
 * a step code to a tenant's own door and the store decides who takes it.
 * Which is the point — an external participant is a peer that can say what
 * should happen, not only a pair of hands for what somebody else decided.
 *
 * <p><b>Why the tenant's door and not the deployment's.</b> The run is about
 * the hospital's data, so it belongs to the hospital: it is authored on the
 * hospital's surface, carried on the hospital's work stream, reported back
 * through the hospital's own lane, and refused by the hospital's own rules if
 * it breaks one. A door of the deployment's would have produced a run with no
 * owner, and there is no orchestrator here for it to belong to instead.
 */
@Component
public final class AskingForADirectoryCheck {

    /** The step the DEPLOYMENT declares. This application performs none of it. */
    public static final String STEP = "fleet.directory.check";

    private final DboInitiator initiator;

    AskingForADirectoryCheck(DboInitiator initiator) {
        this.initiator = initiator;
    }

    /**
     * Asks a tenant to have one of its organisations checked.
     *
     * <p>Three slots, three shapes, and the difference between them is the
     * whole of what this method is for.
     *
     * @param tenant         whose directory it is
     * @param identifier the Organization this run is about, named by what
     *                   this application KNOWS about it rather than by the id
     *                   this store gave it. It has never read the record and
     *                   may not be entitled to: the hospital resolves the
     *                   search against its own records, records what it
     *                   matched, and the object never goes over this wire
     */
    public DboInitiator.Started about(String tenant, String identifier) {
        // GIVEN, not referred. This has no record anywhere — it is what this
        // application proposes the entry should say, and there is nothing for a
        // reference to point at. It is sent with the run, read by whoever
        // performs the step, and stored by nobody unless that step decides it
        // should be.
        String proposed = """
                {"resourceType":"Organization","name":"Hogwarts Infirmary (proposed)",
                 "identifier":[{"system":"urn:rl:org","value":"RL-ORG-1"}]}""";

        // SEVERAL, given. A repeating slot in the order they are meant to be
        // read, because a list somebody sends is a list they meant.
        List<String> notes = List.of(
                note("the sign over the door still says Hospital Wing"),
                note("telephone number is the one for the greenhouses"));

        return initiator.starting(tenant, STEP, Map.of(
                // BY WHAT IT KNOWS. An id would have had to be looked up
                // first, over a surface this application holds no credential
                // for — the work credential it polls with is refused by the
                // hospital's records door, deliberately.
                "org", DboInitiator.Slot.matching(
                        "Organization?identifier=urn:rl:org|" + identifier),
                "proposed", DboInitiator.Slot.object(proposed),
                "notes", DboInitiator.Slot.objects(notes)));
    }

    private static String note(String text) {
        return """
                {"resourceType":"Basic","code":{"text":"%s"}}""".formatted(text);
    }
}
