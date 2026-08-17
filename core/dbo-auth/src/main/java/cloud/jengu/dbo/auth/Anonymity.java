package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.identity.AnonymityEvent;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * Whether a subject is anonymous on purpose, and the refusal that makes the
 * declaration mean something (dbo#39).
 */
public final class Anonymity {

    private Anonymity() {
    }

    /**
     * Records a declaration or its lifting.
     *
     * <p>Declaring anonymity for a subject that is <b>currently identified</b>
     * is refused. The two states would contradict each other, and one of them
     * would be a lie in a record somebody later relies on — so the identity is
     * withdrawn first, deliberately, rather than being silently dropped by a
     * declaration.
     */
    public static String record(ObjectStore store, AnonymityEvent event) {
        if (event.kind() == AnonymityEvent.Kind.DECLARED
                && !Bindings.current(store, event.subjectId()).isEmpty()) {
            throw new AnonymityRefusedException(
                    "subject " + event.subjectId() + " is currently identified — withdraw the "
                            + "binding first. Declaring anonymity over a standing identity would "
                            + "leave a record saying both, and silently dropping the identity "
                            + "would be a decision nobody made");
        }
        return store.put(PutRequest.create("AnonymityEvent", render(event))).id();
    }

    /** Whether this subject is anonymous by declaration rather than by not being known yet. */
    public static boolean declared(ObjectStore store, String subjectId) {
        boolean standing = false;
        for (StoredObject event : eventsFor(store, subjectId)) {
            Object node = Json.parse(new String(event.payload(), StandardCharsets.UTF_8));
            standing = AnonymityEvent.Kind.DECLARED.name().equals(Json.str(node, "kind"));
        }
        return standing;
    }

    /** Everything ever declared about this subject's anonymity, oldest first. */
    public static List<StoredObject> history(ObjectStore store, String subjectId) {
        return eventsFor(store, subjectId);
    }

    private static List<StoredObject> eventsFor(ObjectStore store, String subjectId) {
        return store.getByIdentifier("AnonymityEvent",
                        List.of(new Identifier(IdentityModel.BINDING_SUBJECT_SYSTEM, subjectId)))
                .stream()
                .sorted(Comparator.comparing(StoredObject::lastUpdated)
                        .thenComparing(StoredObject::id))
                .toList();
    }

    private static byte[] render(AnonymityEvent event) {
        StringBuilder json = new StringBuilder("{\"kind\":\"").append(event.kind())
                .append("\",\"subjectId\":").append(Json.quote(event.subjectId()))
                .append(",\"actor\":").append(Json.quote(event.actor()))
                .append(",\"at\":\"").append(event.at())
                .append("\",\"basis\":").append(Json.quote(event.basis()));
        if (event.because() != null) {
            json.append(",\"because\":").append(Json.quote(event.because()));
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** An identification the subject's declared anonymity forbids. */
    public static class AnonymityRefusedException extends RuntimeException {
        public AnonymityRefusedException(String message) {
            super(message);
        }
    }
}
