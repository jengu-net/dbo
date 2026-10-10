package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.core.api.StoreUnreachableException;
import cloud.jengu.dbo.runner.transport.StreamCarrier;
import cloud.jengu.dbo.samples.worker.WhatCrossesTheSocket.Kind;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The worker's end of a stream carried over a WebSocket of the clinic's.
 *
 * <p>A carrier is what moves the store's stream between a participant and a
 * tenant's door, and this one is a socket: the JDK's own client, one
 * connection per tenant, to {@code <base>/<tenant>}. It moves opaque text and
 * nothing else. The ask is signed by the store's lane before it gets here and
 * checked by the store's door after it leaves the other end; the answer is
 * sealed or not by the store, never by this. So the socket needs no credential
 * of its own, and a deployment puts TLS on it for the same reason it puts TLS
 * on any port: so nobody learns which runs exist.
 *
 * <p><b>A dropped socket is reconnected on the next ask, not by a timer.</b>
 * The runner asks on every poll — a heartbeat at least — so a worker that lost
 * its connection is back within one poll of the door answering again, and a
 * worker whose door is gone hears {@link StoreUnreachableException}, which a
 * runner reads as "try again", rather than an answer that never comes.
 */
public final class AskingOverASocket implements StreamCarrier {

    private static final Duration CONNECT = Duration.ofSeconds(5);
    private static final Duration SEND = Duration.ofSeconds(10);
    /** How long a held answer is waited for: it is already made, so only the socket is slow. */
    private static final Duration COLLECTING = Duration.ofSeconds(30);

    private final URI base;
    private final HttpClient client = HttpClient.newHttpClient();

    /** @param base where the clinic's sockets answer, for example {@code ws://host:8080/stream/} */
    public AskingOverASocket(URI base) {
        String said = base.toString();
        this.base = URI.create(said.endsWith("/") ? said : said + "/");
    }

    @Override
    public Door door(String tenant) {
        throw new UnsupportedOperationException("the door is the clinic's: this end only asks");
    }

    @Override
    public Asker asker(String tenant, String participant) {
        return new OverTheSocket(base.resolve(tenant));
    }

    /** One tenant's door, asked over one socket at a time. */
    private final class OverTheSocket implements Asker {

        private final URI door;
        /** What is waited for, by the key its answer comes back under. */
        private final Map<String, CompletableFuture<WhatCrossesTheSocket>> waiting =
                new ConcurrentHashMap<>();
        private final List<Runnable> listening = new CopyOnWriteArrayList<>();
        private volatile WebSocket socket;
        private volatile boolean closed;

        OverTheSocket(URI door) {
            this.door = door;
        }

        // --8<-- [start:ask]
        @Override
        public Optional<String> ask(String id, String ask, Duration patience) {
            return carried(WhatCrossesTheSocket.of(Kind.ASK, id, ask), "answer " + id, patience)
                    .map(WhatCrossesTheSocket::text);
        }

        @Override
        public Optional<String> collect(String key) {
            return carried(WhatCrossesTheSocket.of(Kind.COLLECT, key, ""), "held " + key,
                    COLLECTING).filter(answer -> answer.kind() == Kind.HELD)
                    .map(WhatCrossesTheSocket::text);
        }

        @Override
        public AutoCloseable listen(Runnable woken) {
            listening.add(woken);
            return () -> listening.remove(woken);
        }
        // --8<-- [end:ask]

        /**
         * Sends one message and waits for the one keyed to it.
         *
         * <p>Waiting is registered before sending, so an answer that beats
         * the return of the send is not lost, and a socket that drops while
         * the ask is out fails it at once rather than at patience.
         */
        private Optional<WhatCrossesTheSocket> carried(WhatCrossesTheSocket message,
                String awaited, Duration patience) {
            CompletableFuture<WhatCrossesTheSocket> answer = new CompletableFuture<>();
            waiting.put(awaited, answer);
            try {
                send(message);
                return Optional.of(answer.get(patience.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException none) {
                return Optional.empty();
            } catch (ExecutionException dropped) {
                throw dropped.getCause() instanceof StoreUnreachableException unreachable
                        ? unreachable
                        : new StoreUnreachableException(door + ": " + dropped.getCause(),
                                dropped.getCause());
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } finally {
                waiting.remove(awaited);
            }
        }

        /** One message at a time: the JDK's socket refuses a send while another is out. */
        private synchronized void send(WhatCrossesTheSocket message) {
            WebSocket open = connected();
            try {
                open.sendText(message.written(), true).get(SEND.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException notSent) {
                dropped(open, "the socket would not take a message");
                throw new StoreUnreachableException(door + ": the socket would not take the "
                        + "message", notSent);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                throw new StoreUnreachableException(door + ": interrupted while sending");
            }
        }

        private WebSocket connected() {
            WebSocket open = socket;
            if (open != null && !open.isOutputClosed() && !open.isInputClosed()) {
                return open;
            }
            if (closed) {
                throw new StoreUnreachableException(door + ": this end was closed");
            }
            try {
                open = client.newWebSocketBuilder().connectTimeout(CONNECT)
                        .buildAsync(door, new Arriving()).get(CONNECT.toMillis() * 2,
                                TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException refused) {
                throw new StoreUnreachableException(door + ": no door answers on the socket",
                        refused);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                throw new StoreUnreachableException(door + ": interrupted while connecting");
            }
            socket = open;
            return open;
        }

        /** Everything out on a socket that dropped is failed now, as unreachable. */
        private void dropped(WebSocket gone, String why) {
            if (socket == gone) {
                socket = null;
            }
            StoreUnreachableException unreachable =
                    new StoreUnreachableException(door + ": the socket dropped: " + why);
            waiting.values().forEach(each -> each.completeExceptionally(unreachable));
        }

        /** What one socket hears, put back together from its parts. */
        private final class Arriving implements WebSocket.Listener {

            private final StringBuilder parts = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket from, CharSequence part, boolean last) {
                parts.append(part);
                if (last) {
                    WhatCrossesTheSocket message = WhatCrossesTheSocket.read(parts.toString());
                    parts.setLength(0);
                    switch (message.kind()) {
                        case ANSWER -> complete("answer " + message.key(), message);
                        case HELD, GONE -> complete("held " + message.key(), message);
                        case WAKE -> listening.forEach(Runnable::run);
                        default -> {
                            // A door does not ask; nothing else is said to a worker.
                        }
                    }
                }
                from.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket from, int status, String reason) {
                dropped(from, status + " " + reason);
                return null;
            }

            @Override
            public void onError(WebSocket from, Throwable error) {
                dropped(from, String.valueOf(error.getMessage()));
            }

            private void complete(String key, WhatCrossesTheSocket message) {
                CompletableFuture<WhatCrossesTheSocket> waiter = waiting.get(key);
                if (waiter != null) {
                    waiter.complete(message);
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            WebSocket open = socket;
            socket = null;
            if (open != null) {
                open.sendClose(WebSocket.NORMAL_CLOSURE, "the worker let go").exceptionally(
                        notSaid -> {
                            open.abort();
                            return null;
                        });
            }
        }
    }
}
