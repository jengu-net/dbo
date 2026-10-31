package cloud.jengu.dbo.work;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Asks a tenant for a run of a step, and hears back how it ended.
 *
 * <p>The other half of being a participant: a run exists because somebody
 * asked for one, since this store has no orchestrator and nothing schedules
 * anything on a tenant's behalf. Which performer takes the run is decided by
 * who is entitled to; whoever asked is owed the answer.
 *
 * <p>Framework-free, with one binding per way of reaching a tenant. In the
 * container that serves the tenants it is a service, starting runs in the
 * tenant's own process through the same path its step door takes; an
 * application reaching a tenant over HTTP holds one bound to that door. The
 * answers are the door's either way — the status it answers with and the body
 * — so a caller cannot tell which it holds.
 */
public interface RunInitiator {

    /**
     * How one slot is filled.
     *
     * <p>A type rather than a string, because a reference and an object cannot
     * be told apart once both are strings: {@code "Organization/123"} is a
     * reference and {@code "{\"resourceType\":…}"} is an object, and a caller
     * that meant one while the wire read the other would have the run refused
     * at the door at best and filled wrongly at worst.
     */
    record Slot(Kind kind, List<String> values, boolean many) {

        /** Whether the values are references or the objects themselves. */
        public enum Kind { REFERENCE, OBJECT }

        /** One reference, to something the tenant already holds. */
        public static Slot reference(String reference) {
            return new Slot(Kind.REFERENCE, List.of(reference), false);
        }

        /**
         * One reference, written as a search the tenant resolves when the run
         * is authored — for a record known by something about it rather than
         * by the id this store gave it. Refused if it matches none, or several
         * where the slot takes one.
         */
        public static Slot matching(String query) {
            return new Slot(Kind.REFERENCE, List.of(query), false);
        }

        /** Several references. */
        public static Slot references(List<String> references) {
            return new Slot(Kind.REFERENCE, List.copyOf(references), true);
        }

        /** One object, sent with the run: its JSON, as the caller has it. */
        public static Slot object(String json) {
            return new Slot(Kind.OBJECT, List.of(json), false);
        }

        /** Several such objects, in the order they are meant to be read. */
        public static Slot objects(List<String> json) {
            return new Slot(Kind.OBJECT, List.copyOf(json), true);
        }

        /** The slot as a request names it: a quoted reference, or the object itself. */
        public String rendered() {
            StringBuilder out = new StringBuilder();
            if (many) {
                out.append('[');
            }
            for (int at = 0; at < values.size(); at++) {
                out.append(at == 0 ? "" : ",")
                        .append(kind == Kind.REFERENCE ? quoted(values.get(at)) : values.get(at));
            }
            if (many) {
                out.append(']');
            }
            return out.toString();
        }
    }

    /** What the tenant answered, and the run it named if it started one. */
    record Started(int status, String run, String key, String body) {

        /** 201: asking for a run creates one, or finds the one its key already names. */
        public boolean accepted() {
            return status == 201;
        }

        public String runOrFail() {
            if (!accepted()) {
                throw new IllegalStateException("no run was started: " + status + " " + body);
            }
            return run;
        }
    }

    /**
     * What a run answered the caller that asked for it.
     *
     * @param status 200 with the run, 404 for a run this caller did not ask
     *               for or that does not exist — deliberately the same answer
     * @param body   the run as its tenant renders a task on a 200, the refusal
     *               otherwise
     */
    record Answer(int status, String body) {

        /** Whether the run answered at all. */
        public boolean answered() {
            return status == 200;
        }

        /** The task's status — {@code in-progress}, {@code completed}, … — or null unanswered. */
        public String state() {
            return answered() ? field(body, "status") : null;
        }

        /**
         * Whether the work is over: completed, failed or cancelled. A task
         * waiting — for a machine or for a person — is not, and neither is
         * one somebody holds.
         */
        public boolean settled() {
            String state = state();
            return "completed".equals(state) || "failed".equals(state)
                    || "cancelled".equals(state);
        }
    }

    /**
     * Asks a tenant to start a run of a step, one reference per slot.
     *
     * @param inputs {@code slot -> "Type/id"}
     */
    default Started start(String tenant, String step, Map<String, String> inputs) {
        Map<String, Slot> filled = new java.util.LinkedHashMap<>();
        inputs.forEach((slot, reference) -> filled.put(slot, Slot.reference(reference)));
        return starting(tenant, step, filled);
    }

    /** The same, for slots that carry objects, or several of anything. */
    default Started starting(String tenant, String step, Map<String, Slot> inputs) {
        return starting(tenant, step, inputs, null);
    }

    /**
     * The same, under a key of the caller's: asking again with the same key
     * finds the run already started rather than starting another, so a
     * decision made twice — on two nodes, or after a retry — is one run.
     *
     * @param key what makes the run this one, such as who, which step, what
     *            happened and when to the minute; null for a run of its own
     */
    Started starting(String tenant, String step, Map<String, Slot> inputs, String key);

    /**
     * The same, for work only one participant can do: offered to the one
     * named and taken by no other, and waiting for it while it is away.
     *
     * <p>Name it as a {@link ContactListener} told you of it — its
     * {@link ContactListener.Worker#client() client} and
     * {@link ContactListener.Worker#name() name}.
     *
     * <p>Refused, not ignored, where an initiator cannot carry it: started
     * without it, the run would go to whoever polled first.
     *
     * @param addressee who alone may take the run, or null for whoever holds
     *                  its step
     */
    default Started starting(String tenant, String step, Map<String, Slot> inputs, String key,
            Run.Addressee addressee) {
        if (addressee == null) {
            return starting(tenant, step, inputs, key);
        }
        throw new UnsupportedOperationException(getClass().getName()
                + " cannot start a run for one participant");
    }

    /**
     * The same, as a part of another run of the tenant's: started for it, and
     * read as one of the things it set going. The run named must be one the
     * tenant holds, open or over; one it does not hold is refused.
     *
     * <p>Refused, not ignored, where an initiator cannot carry it: started
     * without it, the run would read as a root and say nothing of why.
     *
     * @param partOf the key of the run this one is part of, or null for a root
     */
    default Started starting(String tenant, String step, Map<String, Slot> inputs, String key,
            Run.Addressee addressee, String partOf) {
        if (partOf == null) {
            return starting(tenant, step, inputs, key, addressee);
        }
        throw new UnsupportedOperationException(getClass().getName()
                + " cannot start a run as part of another");
    }

    /**
     * The same, as work that leaves this tenant for another through their
     * zone — or for the tenant's own zone, or from a zone to one of its
     * members — under the rules the tenant's door holds a crossing to.
     *
     * <p>Refused, not ignored, where an initiator cannot carry it: started
     * without it, the work would stay where it was asked for.
     *
     * @param crossingTo the tenant the work goes to, or null for work that
     *                   stays
     */
    default Started starting(String tenant, String step, Map<String, Slot> inputs, String key,
            Run.Addressee addressee, String partOf, String crossingTo) {
        if (crossingTo == null) {
            return starting(tenant, step, inputs, key, addressee, partOf);
        }
        throw new UnsupportedOperationException(getClass().getName()
                + " cannot start work that crosses to another tenant");
    }

    /**
     * Says something of lasting work: {@code accept}, {@code reject},
     * {@code stage} or {@code end}, as the run's own door takes it.
     *
     * @param run  the run's id
     * @param said {@code because}, and {@code stage} or {@code status} where
     *             the act names one
     */
    default Started saying(String tenant, String run, String act, Map<String, String> said) {
        throw new UnsupportedOperationException(getClass().getName()
                + " cannot say anything of lasting work");
    }

    /**
     * The same initiator, acting for a person: a run it starts names them as
     * having asked, on the run and on its Task, and an act it says of lasting
     * work is on the trail as theirs. Without one, both are the application's.
     *
     * <p>Trusted as {@code Caller.setChain} is: the application already names
     * who a write is for, and names who work is for the same way.
     *
     * <p>Refused, not ignored, where an initiator cannot carry it: started or
     * said without the person, the trail would name the application where it
     * should name who.
     *
     * @param person a reference, such as {@code Practitioner/<id>}, as a door
     *               records a credential's {@code fhirUser}; null for the
     *               application itself
     */
    default RunInitiator actingFor(String person) {
        if (person == null) {
            return this;
        }
        throw new UnsupportedOperationException(getClass().getName()
                + " cannot act for a person");
    }

    /** Asks a run how it stands. */
    Answer answer(String tenant, String run);

    /**
     * Asks a run how it stands until it has come to rest, or until patience
     * runs out — polling, because the store tells nobody when a run ends: a
     * run is a record, and its end is a version of it. Out of patience, the
     * last answer is returned: "still in progress" is an answer.
     */
    default Answer awaiting(String tenant, String run, Duration patience) {
        long until = System.nanoTime() + patience.toNanos();
        long pause = 100;
        Answer last = answer(tenant, run);
        while (last.answered() && !last.settled() && System.nanoTime() < until) {
            try {
                Thread.sleep(Math.min(pause,
                        Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return last;
            }
            pause = Math.min(pause * 2, 2_000);
            last = answer(tenant, run);
        }
        return last;
    }

    /** One string field of a JSON answer, without a parser the caller may not have. */
    static String field(String body, String name) {
        String at = "\"" + name + "\":\"";
        int start = body == null ? -1 : body.indexOf(at);
        if (start < 0) {
            return null;
        }
        int from = start + at.length();
        int end = body.indexOf('"', from);
        return end < 0 ? null : body.substring(from, end);
    }

    /** A string as JSON. */
    static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The step door's {@code partOf}, as a member to append to a request body
     * after another: empty for a root.
     */
    static String partOf(String key) {
        return key == null ? "" : ",\"partOf\":" + quoted(key);
    }

    /**
     * The step door's {@code crossing}, as a member to append to a request
     * body after another: empty for work that stays.
     */
    static String crossing(String to) {
        return to == null ? "" : ",\"crossing\":{\"to\":" + quoted(to) + "}";
    }

    /**
     * The step door's {@code for}, as a member to append to a request body
     * after another: empty for a run naming nobody.
     */
    static String addressed(Run.Addressee addressee) {
        if (addressee == null) {
            return "";
        }
        if (addressee.provider() != null) {
            // The door hands work to the deployment's provider only.
            return ",\"for\":{\"provider\":true}";
        }
        return ",\"for\":{" + (addressee.client() == null ? ""
                : "\"client\":" + quoted(addressee.client())
                        + (addressee.executor() == null ? "" : ","))
                + (addressee.executor() == null ? ""
                        : "\"executor\":" + quoted(addressee.executor())) + "}";
    }
}
