package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.identity.BindingEvent;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who a subject is currently identified as, folded from what was recorded.
 *
 * <p>The events are the truth and the answer is derived, rather than the
 * answer being stored and the events being a log beside it. That is what makes
 * "this was bound on Tuesday and withdrawn on Thursday" survivable: there is
 * no current-state field for a withdrawal to overwrite.
 */
public final class Bindings {

    private Bindings() {
    }

    /**
     * Records an attachment or a detachment. Never edits: both are appended.
     *
     * <p>Attaching an identity to a subject who is <b>anonymous by
     * declaration</b> is refused. That refusal is the entire point of the
     * declaration: without it, a helpful workflow identifies somebody who had
     * a right not to be, and everybody involved believes they were being
     * careful.
     *
     * <p>Detaching is always allowed. It is the corrective direction, and a
     * subject who has since asked to be anonymous is exactly who most needs an
     * earlier identification undone.
     */
    public static String record(ObjectStore store, BindingEvent event) {
        if (event.kind() == BindingEvent.Kind.BOUND
                && Anonymity.declared(store, event.subjectId())) {
            throw new Anonymity.AnonymityRefusedException(
                    "subject " + event.subjectId() + " is anonymous by declaration — identifying "
                            + "them is refused rather than merely discouraged, because a prompt "
                            + "somebody can click through is not a protection");
        }
        return store.put(PutRequest.create("BindingEvent", render(event))).id();
    }

    /**
     * The identities currently attached to a subject.
     *
     * <p>Folded newest-last, so a withdrawal after a binding wins and a
     * re-binding after a withdrawal wins again — a mistaken withdrawal is as
     * recoverable as a mistaken binding, which it has to be, since both are
     * decisions a person made in a hurry.
     */
    public static Set<String> current(ObjectStore store, String subjectId) {
        Set<String> bound = new LinkedHashSet<>();
        for (StoredObject event : eventsFor(store, subjectId)) {
            Object node = Json.parse(new String(event.payload(), StandardCharsets.UTF_8));
            String identity = Json.str(node, "identityId");
            if (BindingEvent.Kind.BOUND.name().equals(Json.str(node, "kind"))) {
                bound.add(identity);
            } else {
                bound.remove(identity);
            }
        }
        return bound;
    }

    /**
     * How well the standing binding between this subject and this identity was
     * established, or {@code NONE} if there is none.
     *
     * <p>Feeds the chain rule: what somebody may do is bounded by the weaker
     * of how they authenticated now and how well this identification was made.
     * A national eID presented today does not upgrade an identification
     * somebody made last year from a photocopy.
     */
    public static cloud.jengu.dbo.core.api.identity.Assurance assuranceOf(
            ObjectStore store, String subjectId, String identityId) {
        cloud.jengu.dbo.core.api.identity.Assurance standing =
                cloud.jengu.dbo.core.api.identity.Assurance.NONE;
        for (StoredObject event : eventsFor(store, subjectId)) {
            Object node = Json.parse(new String(event.payload(), StandardCharsets.UTF_8));
            if (!identityId.equals(Json.str(node, "identityId"))) {
                continue;
            }
            standing = BindingEvent.Kind.BOUND.name().equals(Json.str(node, "kind"))
                    ? cloud.jengu.dbo.core.api.identity.Assurance.valueOf(
                            Json.str(node, "assurance"))
                    : cloud.jengu.dbo.core.api.identity.Assurance.NONE;
        }
        return standing;
    }

    /**
     * Everything ever recorded about this subject's identity, oldest first.
     *
     * <p>The evidence half: that somebody was identified, and that it was
     * undone, remains answerable after the identity is gone.
     */
    public static List<StoredObject> history(ObjectStore store, String subjectId) {
        return eventsFor(store, subjectId);
    }

    private static List<StoredObject> eventsFor(ObjectStore store, String subjectId) {
        return store.getByIdentifier("BindingEvent",
                        List.of(new Identifier(IdentityModel.BINDING_SUBJECT_SYSTEM, subjectId)))
                .stream()
                .sorted(Comparator.comparing(StoredObject::lastUpdated)
                        .thenComparing(StoredObject::id))
                .toList();
    }

    private static byte[] render(BindingEvent event) {
        StringBuilder json = new StringBuilder("{\"kind\":\"").append(event.kind())
                .append("\",\"identityId\":").append(Json.quote(event.identityId()))
                .append(",\"subjectId\":").append(Json.quote(event.subjectId()))
                .append(",\"assurance\":\"").append(event.assurance()).append('"')
                .append(",\"actor\":").append(Json.quote(event.actor()))
                .append(",\"at\":\"").append(event.at())
                .append("\",\"purpose\":").append(Json.quote(event.purpose()));
        if (event.because() != null) {
            json.append(",\"because\":").append(Json.quote(event.because()));
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }
}
