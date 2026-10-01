package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The clinic asking for the human behind a record to be held as who they are.
 *
 * <p>A patient record is somebody in one capacity; a {@code Person} is the
 * human, carrying the number they are known by and linking the records that
 * are theirs. Whether a second claim on a number or a record is the same
 * human or a mistake is the hospital's to decide, against its own identity
 * rules — so this asks, and hears back either the person written or the
 * hospital's reason for refusing.
 */
@Component
public final class AskingWhoSomebodyIs {

    /** The step a tenant holding the people behind its records declares. */
    public static final String STEP = "care.records.identify";

    private final DboInitiator initiator;

    AskingWhoSomebodyIs(DboInitiator initiator) {
        this.initiator = initiator;
    }

    /**
     * @param tenant the hospital
     * @param person the {@code Person}, as this application has them
     */
    public DboInitiator.Started identify(String tenant, String person) {
        return initiator.starting(tenant, STEP,
                Map.of("person", DboInitiator.Slot.object(person)));
    }
}
