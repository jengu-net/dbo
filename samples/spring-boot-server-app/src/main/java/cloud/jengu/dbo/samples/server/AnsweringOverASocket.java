package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.runner.transport.StreamCarrier;
import cloud.jengu.dbo.samples.worker.WhatCrossesTheSocket;
import cloud.jengu.dbo.samples.worker.WhatCrossesTheSocket.Kind;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The clinic's end of a stream carried over a WebSocket: the doors.
 *
 * <p>The store opens one door per tenant on every carrier the application
 * registers, and hands it what answers an ask. This carrier's door is the
 * sockets connected at {@code /stream/<tenant>}: an ask arriving on one is
 * handed to the door's answering as it came, the answer goes back on the same
 * socket under the ask's key, an answer too large to travel in one message is
 * held here until the worker collects it, and a wake-up goes to every socket
 * on the tenant.
 *
 * <p><b>Nothing here decides anything.</b> Who is asking, whether the ask is
 * theirs to make, and whether its bytes are the ones they signed is the
 * store's, behind the answering; a socket that is not anybody's in particular
 * is fine, because an ask nobody enrolled signed is refused there. A tenant
 * with no door on this carrier — not serving yet, or the carrier withdrawn —
 * closes the socket at once, and the worker hears its store is unreachable.
 */
public final class AnsweringOverASocket implements StreamCarrier {

    private final Map<String, SocketDoor> doors = new ConcurrentHashMap<>();

    @Override
    public Door door(String tenant) {
        SocketDoor door = new SocketDoor(tenant);
        doors.put(tenant, door);
        return door;
    }

    @Override
    public Asker asker(String tenant, String participant) {
        throw new UnsupportedOperationException("a worker asks over this socket from its own "
                + "end; the clinic's end only answers");
    }

    /** The endpoint one connection is served by. */
    public Endpoint connection() {
        return new Connection(this);
    }

    /** One tenant's door: the sockets connected to it, and what it holds for them. */
    private final class SocketDoor implements Door {

        private final String tenant;
        private final Set<Session> sockets = ConcurrentHashMap.newKeySet();
        private final Map<String, String> held = new ConcurrentHashMap<>();
        private volatile Answering answering;

        SocketDoor(String tenant) {
            this.tenant = tenant;
        }

        // --8<-- [start:door]
        @Override
        public void serve(Answering answering) {
            this.answering = answering;
        }

        @Override
        public void wake() {
            sockets.forEach(socket -> send(socket, WhatCrossesTheSocket.of(Kind.WAKE, tenant, "")));
        }

        @Override
        public String hold(String id, String answer) {
            held.put(id, answer);
            return id;
        }
        // --8<-- [end:door]

        @Override
        public void close() {
            doors.remove(tenant, this);
            sockets.forEach(socket -> AnsweringOverASocket.close(socket,
                    CloseReason.CloseCodes.GOING_AWAY, "the door closed"));
            sockets.clear();
        }

        /** What one message from a worker comes to: an answer, or a held one handed over. */
        // --8<-- [start:carried]
        private void carried(Session socket, WhatCrossesTheSocket message) {
            switch (message.kind()) {
                case ASK -> send(socket, WhatCrossesTheSocket.of(Kind.ANSWER, message.key(),
                        answering.answer(message.key(), message.text())));
                case COLLECT -> {
                    String answer = held.remove(message.key());
                    send(socket, answer == null
                            ? WhatCrossesTheSocket.of(Kind.GONE, message.key(), "")
                            : WhatCrossesTheSocket.of(Kind.HELD, message.key(), answer));
                }
                default -> {
                    // A worker asks and collects; nothing else is said to a door.
                }
            }
        }
        // --8<-- [end:carried]
    }

    /**
     * One worker's socket, read in parts because an ask has no size the
     * socket knows about: a whole message buffered by the server would be a
     * limit somebody else chose.
     */
    public static final class Connection extends Endpoint {

        private final AnsweringOverASocket carrier;

        Connection(AnsweringOverASocket carrier) {
            this.carrier = carrier;
        }

        @Override
        public void onOpen(Session socket, EndpointConfig config) {
            String tenant = socket.getPathParameters().get("tenant");
            SocketDoor door = tenant == null ? null : carrier.doors.get(tenant);
            if (door == null || door.answering == null) {
                close(socket, CloseReason.CloseCodes.TRY_AGAIN_LATER,
                        "no door is open for this tenant on the socket");
                return;
            }
            door.sockets.add(socket);
            StringBuilder parts = new StringBuilder();
            MessageHandler.Partial<String> arriving = (part, last) -> {
                parts.append(part);
                if (last) {
                    String message = parts.toString();
                    parts.setLength(0);
                    door.carried(socket, WhatCrossesTheSocket.read(message));
                }
            };
            socket.addMessageHandler(String.class, arriving);
        }

        @Override
        public void onClose(Session socket, CloseReason reason) {
            carrier.doors.values().forEach(door -> door.sockets.remove(socket));
        }
    }

    /**
     * One message onto one socket. A send that fails is let go: the worker's
     * ask times out or its socket drops, and it asks again.
     */
    private static void send(Session socket, WhatCrossesTheSocket message) {
        // One at a time per socket: the container refuses a second send while
        // one is writing, and a wake-up may come from any thread.
        synchronized (socket) {
            try {
                socket.getBasicRemote().sendText(message.written());
            } catch (IOException | IllegalStateException gone) {
                close(socket, CloseReason.CloseCodes.CLOSED_ABNORMALLY, "a send failed");
            }
        }
    }

    private static void close(Session socket, CloseReason.CloseCode code, String why) {
        try {
            socket.close(new CloseReason(code, why));
        } catch (IOException | IllegalStateException alreadyGone) {
            // Closing a socket that is already closed is what was wanted.
        }
    }
}
