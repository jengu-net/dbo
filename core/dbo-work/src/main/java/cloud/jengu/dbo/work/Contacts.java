package cloud.jengu.dbo.work;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * One node's contact with the workers of the steps somebody listens to.
 *
 * <p>In memory and nowhere else: a request from a worker updates what this
 * node last heard of it and tells the listeners, and nothing is written. A
 * step with no listener is not tracked at all, so it costs nothing.
 *
 * <p><b>One timer per worker a listener has heard</b>, armed for that
 * listener's silence. When it fires, a worker heard again meanwhile has its
 * timer moved on; one that has not is unknown, and forgotten.
 *
 * <p>Events are delivered in order on the scheduler's thread, never on the
 * thread of the request that caused them: a listener that starts work, or
 * waits on anything, must not hold a worker's verb open while it does.
 */
public final class Contacts implements AutoCloseable {

    private final String node;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final BiConsumer<String, RuntimeException> failed;
    private final List<ContactListener> listeners = new CopyOnWriteArrayList<>();
    /** What each listener last heard, per tenant and worker. */
    private final Map<Key, Heard> heard = new LinkedHashMap<>();

    private record Key(ContactListener listener, String tenant, ContactListener.Worker worker) {
    }

    private static final class Heard {
        Instant last;
        ScheduledFuture<?> timer;
    }

    /**
     * @param node      the name every event carries
     * @param clock     what "now" is
     * @param scheduler where events are delivered and silences timed; null
     *                  delivers on the caller's thread and times nothing, for
     *                  a caller that asks {@link #expire()} itself
     * @param failed    told when a listener throws, with its class name
     */
    public Contacts(String node, Clock clock, ScheduledExecutorService scheduler,
            BiConsumer<String, RuntimeException> failed) {
        if (node == null || node.isBlank()) {
            throw new IllegalArgumentException("contact is per node, and the node is named");
        }
        this.node = node;
        this.clock = clock;
        this.scheduler = scheduler;
        this.failed = failed;
    }

    /** The name every event from here carries. */
    public String node() {
        return node;
    }

    /**
     * Takes a listener up, and tells it everything about its step is unknown
     * here.
     *
     * @throws IllegalArgumentException naming the listener, when it names no
     *         step or declares no silence
     */
    public void listen(ContactListener listener) {
        refuseWithoutASilence(listener);
        listeners.add(listener);
        deliver(listener, () -> listener.reset(new ContactListener.Reset(listener.step(), node)));
    }

    /**
     * The refusal a registration meets, said where it can be said first.
     *
     * <p>Public so a host that knows its listeners before any of them is
     * registered refuses at startup in the same words.
     */
    public static void refuseWithoutASilence(ContactListener listener) {
        String named = listener.getClass().getName();
        if (listener.step() == null || listener.step().isBlank()) {
            throw new IllegalArgumentException(named + " listens to no step; a contact "
                    + "listener names the step whose workers it is told about");
        }
        Duration silence = listener.silence();
        if (silence == null || silence.isZero() || silence.isNegative()) {
            throw new IllegalArgumentException(named + " declares no silence for '"
                    + listener.step() + "'. A contact listener says how long a worker may "
                    + "say nothing before it is unknown, and there is no default: the store "
                    + "imposes no freshness rule of its own");
        }
    }

    /** Lets a listener go, with everything it had heard. */
    public void forget(ContactListener listener) {
        listeners.remove(listener);
        synchronized (heard) {
            heard.entrySet().removeIf(entry -> {
                if (entry.getKey().listener() != listener) {
                    return false;
                }
                cancel(entry.getValue());
                return true;
            });
        }
    }

    /** Whether anybody listens at all, which is what decides whether activity is worth reading. */
    public boolean listening() {
        return !listeners.isEmpty();
    }

    /** The steps somebody listens to. */
    public Set<String> listened() {
        Set<String> steps = new LinkedHashSet<>();
        listeners.forEach(listener -> steps.add(listener.step()));
        return steps;
    }

    /**
     * A worker was heard for the steps named.
     *
     * <p>A step may be named fully or bare, because a poll speaks a run's
     * words and a declaration speaks the catalogue's.
     *
     * @param statistics what a heartbeat carried, or null for any other request
     */
    public void heard(String tenant, Collection<String> steps, ContactListener.Worker worker,
            Map<String, Object> statistics) {
        if (listeners.isEmpty() || steps.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (ContactListener listener : listeners) {
            if (steps.stream().noneMatch(step -> names(listener.step(), step))) {
                continue;
            }
            Key key = new Key(listener, tenant, worker);
            boolean appeared;
            synchronized (heard) {
                Heard last = heard.get(key);
                appeared = last == null;
                if (appeared) {
                    last = new Heard();
                    heard.put(key, last);
                    last.last = now;
                    arm(key, last, listener.silence());
                } else {
                    last.last = now;
                }
            }
            if (appeared) {
                deliver(listener, () -> listener.appeared(new ContactListener.Appeared(tenant,
                        listener.step(), worker, node, now, statistics)));
            } else if (statistics != null) {
                deliver(listener, () -> listener.statistics(new ContactListener.Statistics(
                        tenant, listener.step(), worker, node, now, statistics)));
            }
        }
    }

    /**
     * Every worker silent for longer than its listener's threshold, made
     * unknown now.
     *
     * <p>What a timer does when it fires, for one worker; public so a caller
     * holding the clock can ask for all of them at a moment it chose.
     */
    public void expire() {
        List<Key> silent = new ArrayList<>();
        synchronized (heard) {
            heard.forEach((key, last) -> {
                if (pastSilence(key, last)) {
                    silent.add(key);
                }
            });
        }
        silent.forEach(this::expire);
    }

    private void expire(Key key) {
        Instant lastSeen;
        synchronized (heard) {
            Heard last = heard.get(key);
            if (last == null) {
                return;
            }
            if (!pastSilence(key, last)) {
                arm(key, last, Duration.between(clock.instant(),
                        last.last.plus(key.listener().silence())));
                return;
            }
            heard.remove(key);
            cancel(last);
            lastSeen = last.last;
        }
        ContactListener listener = key.listener();
        deliver(listener, () -> listener.unknown(new ContactListener.Unknown(key.tenant(),
                listener.step(), key.worker(), node, lastSeen)));
    }

    private boolean pastSilence(Key key, Heard last) {
        return !clock.instant().isBefore(last.last.plus(key.listener().silence()));
    }

    private void arm(Key key, Heard last, Duration after) {
        if (scheduler == null) {
            return;
        }
        cancel(last);
        long millis = Math.max(1, after.toMillis());
        last.timer = scheduler.schedule(() -> expire(key), millis, TimeUnit.MILLISECONDS);
    }

    private static void cancel(Heard last) {
        if (last.timer != null) {
            last.timer.cancel(false);
            last.timer = null;
        }
    }

    private void deliver(ContactListener listener, Runnable event) {
        Runnable guarded = () -> {
            try {
                event.run();
            } catch (RuntimeException threw) {
                if (failed != null) {
                    failed.accept(listener.getClass().getName(), threw);
                }
            }
        };
        if (scheduler == null) {
            guarded.run();
        } else {
            scheduler.execute(guarded);
        }
    }

    /** Whether a step named one way is the step listened to, named fully. */
    static boolean names(String listened, String named) {
        if (listened.equals(named)) {
            return true;
        }
        int dot = listened.lastIndexOf('.');
        return dot > 0 && listened.substring(dot + 1).equals(named);
    }

    /** Stops the timers. What was heard is forgotten with the node. */
    @Override
    public void close() {
        synchronized (heard) {
            heard.values().forEach(Contacts::cancel);
            heard.clear();
        }
    }
}
