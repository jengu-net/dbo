package cloud.jengu.dbo.runner.transport;

import java.time.Duration;
import java.util.Optional;

/**
 * What carries the lane's stream protocol between a participant and a
 * tenant's door: the transport SPI's second layer.
 *
 * <p><b>The protocol is the store's; the carrier only carries.</b> An ask is
 * the participant's verb and body, signed over its own bytes with the key the
 * participant enrolled, delivered to one tenant's door; the answer comes back
 * keyed by the ask; an answer too large to travel in a message is held until
 * it is collected; and the door says <em>look again</em> when work appears.
 * The store keeps authentication, authorisation, the signature, sealing and
 * the feed's cursors. A carrier moves opaque asks, answers and wake-ups, reads
 * none of them, and can delay or drop an ask but never widen one: an ask it
 * altered no longer matches its signature and is refused as a forgery.
 *
 * <p>The store's own substrate is one carrier. A host may register another —
 * a socket of its own — and each tenant whose lane it serves opens a door on
 * it too.
 */
public interface StreamCarrier {

    /** The door's end, for one tenant. */
    Door door(String tenant);

    /** A participant's end, onto one tenant's door. */
    Asker asker(String tenant, String participant);

    /** What a door answers one ask with, given the ask's id and its text. */
    @FunctionalInterface
    interface Answering {
        String answer(String id, String ask);
    }

    /** The door's end of a carrier. */
    interface Door extends AutoCloseable {

        /**
         * Starts carrying asks to the answering given, one at a time, and each
         * answer back to the asker keyed by the ask's id. Returns at once.
         */
        void serve(Answering answering);

        /**
         * Tells whoever listens on this tenant that there may be work. Best
         * effort: a wake-up that does not arrive costs a participant the poll
         * interval, and nothing else.
         */
        void wake();

        /**
         * Holds an answer too large to travel in a message until its asker
         * collects it, once.
         *
         * @return the key it waits under
         */
        String hold(String id, String answer);

        @Override
        void close();
    }

    /** A participant's end of a carrier. */
    interface Asker extends AutoCloseable {

        /**
         * Carries one ask to the door and its answer back.
         *
         * @return the answer, or empty when none came within patience
         * @throws cloud.jengu.dbo.core.api.StoreUnreachableException when there
         *         is no door to carry it to
         */
        Optional<String> ask(String id, String ask, Duration patience);

        /** An answer the door held, handed over once; empty when it is not there. */
        Optional<String> collect(String key);

        /**
         * Listens for the door's wake-ups.
         *
         * @return what stops the listening
         */
        AutoCloseable listen(Runnable woken);

        @Override
        void close();
    }
}
