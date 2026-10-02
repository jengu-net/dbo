package cloud.jengu.dbo.spring.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The other half of being a participant: asking for work, not only doing it.
 *
 * <p>A participant is usually described as something that polls a lane and
 * performs what it is handed, and that is half of it. The same application, on
 * the same credential, can <b>author</b> a run — which is how work comes to
 * exist at all, since this store has no orchestrator and nothing schedules
 * anything on a tenant's behalf. A run exists because somebody asked for one.
 *
 * <p>Which makes an external participant a peer rather than a subordinate: it
 * is not told what to do by the deployment, it is one of the things that can
 * say what should be done. Whether it then performs that step itself, or
 * whether a bean somewhere in the deployment does, is not its business — the
 * run is the tenant's, and who takes it is decided by what is entitled to.
 *
 * <p><b>It asks the tenant, always.</b> A step the deployment performs for
 * every tenant is still started on the tenant's own door, because the run
 * belongs to the tenant it is about and a run authored anywhere else would be
 * a run with no owner. That is also why this needs no notion of the fleet: it
 * posts a step code to a tenant, and which level declared that step is the
 * store's business rather than this application's.
 *
 * <p><b>And it hears back.</b> The run answers the application that asked for
 * it, at the run's own address: how it stands, what it was over, and what the
 * step produced — and nothing more. The credential that asks for work holds no
 * door onto the tenant's records, and does not need one to learn how its work
 * ended.
 */
public final class DboInitiator {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.worker");

    private final DboWorkerProperties properties;
    private final Map<String, Supplier<String>> tokens;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    DboInitiator(DboWorkerProperties properties, Map<String, Supplier<String>> tokens) {
        this.properties = properties;
        this.tokens = Map.copyOf(tokens);
    }

    /**
     * How one slot is filled.
     *
     * <p>A type rather than a string, because a reference and an object cannot
     * be told apart once both are strings: {@code "Organization/123"} is a
     * reference and {@code "{\"resourceType\":…}"} is an object, and a caller
     * that meant one while the wire read the other would have the run refused
     * at the door at best and filled wrongly at worst. Saying which is meant
     * costs one word and removes the guess.
     */
    public record Slot(Kind kind, List<String> values, boolean many) {

        /** Whether the values are references or the objects themselves. */
        public enum Kind { REFERENCE, OBJECT }

        /** One reference, to something the tenant already holds. */
        public static Slot reference(String reference) {
            return new Slot(Kind.REFERENCE, List.of(reference), false);
        }

        /**
         * One reference, written as a search the tenant resolves.
         *
         * <p>What this is FOR: an application that knows a record by something
         * about it — an identifier, a business key — and not by the id this
         * store gave it. It need not look the id up first, and need not be
         * able to: the tenant resolves the search against its own records when
         * the run is authored, and the run then records what it matched.
         *
         * <p>Refused if it matches none, or if it matches several and the slot
         * takes one. Both come back as a 400 saying which, because a run over
         * whichever record came back first is a run nobody can account for.
         */
        public static Slot matching(String query) {
            return new Slot(Kind.REFERENCE, List.of(query), false);
        }

        /** Several references. */
        public static Slot references(List<String> references) {
            return new Slot(Kind.REFERENCE, List.copyOf(references), true);
        }

        /** One object, sent with the run: its JSON, as this application has it. */
        public static Slot object(String json) {
            return new Slot(Kind.OBJECT, List.of(json), false);
        }

        /** Several such objects, in the order they are meant to be read. */
        public static Slot objects(List<String> json) {
            return new Slot(Kind.OBJECT, List.copyOf(json), true);
        }

        /** What goes in the request: a quoted reference, or the object itself. */
        private String rendered() {
            StringBuilder out = new StringBuilder();
            if (many) {
                out.append('[');
            }
            for (int at = 0; at < values.size(); at++) {
                out.append(at == 0 ? "" : ",")
                        .append(kind == Kind.REFERENCE ? quote(values.get(at)) : values.get(at));
            }
            if (many) {
                out.append(']');
            }
            return out.toString();
        }
    }

    /** What the tenant answered, and the run it named if it started one. */
    public record Started(int status, String run, String key, String body) {

        /** 201: asking for a run creates one. */
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
     * Asks a tenant to start a run of a step, over the documents named.
     *
     * @param tenant the tenant the work is about, which must be one this
     *               application holds an HTTP lane into
     * @param step   the step code, whether the tenant declared it or the
     *               deployment did
     * @param inputs a reference per slot the step declares, as
     *               {@code slot -> "Type/id"}
     */
    public Started start(String tenant, String step, Map<String, String> inputs) {
        // ONE REFERENCE EACH, which is what almost every run is over. Its own
        // method rather than the general one: naming the shape of every slot to
        // fill three with references would be ceremony over the ordinary thing.
        Map<String, Slot> filled = new java.util.LinkedHashMap<>();
        inputs.forEach((slot, reference) -> filled.put(slot, Slot.reference(reference)));
        return starting(tenant, step, filled);
    }

    /**
     * The same, for slots that carry objects, or several of anything.
     *
     * <p>What each slot may hold is the step's to declare and this does not
     * check it. The tenant's door does, against the declaration, and answers
     * 400 naming the slot and what it takes — which is a better place for the
     * rule than a copy of it here that could disagree.
     *
     * @param inputs a {@link Slot} per slot the step declares
     */
    public Started starting(String tenant, String step, Map<String, Slot> inputs) {
        DboWorkerProperties.Lane lane = askable(tenant);
        Supplier<String> token = tokens.get(tenant);
        StringBuilder named = new StringBuilder();
        inputs.forEach((slot, filled) -> {
            if (named.length() > 0) {
                named.append(',');
            }
            named.append(quote(slot)).append(':').append(filled.rendered());
        });
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(lane.getBase().toString().replaceAll("/+$", "")
                                + "/step/" + step))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":{" + named + "}}"));
        if (token != null) {
            // THE WORK CREDENTIAL, the same one the lane polls with. A
            // participant that may take work of a step may ask for work of it;
            // there is no second credential and no second enrolment.
            request.header("Authorization", "Bearer " + token.get());
        }
        try {
            HttpResponse<String> answered =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String body = answered.body();
            Started started = new Started(answered.statusCode(),
                    valueOf(body, "run"), valueOf(body, "key"), body);
            if (!started.accepted()) {
                // Said once, by name. A run that was not started is the
                // difference between "nothing to do" and "we were refused",
                // and those look identical from everywhere else.
                LOG.warn("a run was not started: tenant={} step={} status={}",
                        tenant, step, answered.statusCode());
            }
            return started;
        } catch (java.io.IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("asking '" + tenant + "' for a run of '" + step
                    + "' did not complete", failed);
        }
    }

    /**
     * What a run answered the application that asked for it.
     *
     * @param status the HTTP status: 200 with the run, 404 for a run this
     *               application did not ask for or that does not exist — the
     *               two are deliberately the same answer
     * @param body   the run as a FHIR {@code Task} on a 200, the refusal
     *               otherwise
     */
    public record Answer(int status, String body) {

        /** Whether the run answered at all. */
        public boolean answered() {
            return status == 200;
        }

        /** The Task's status — {@code in-progress}, {@code completed}, … — or null unanswered. */
        public String state() {
            return answered() ? valueOf(body, "status") : null;
        }

        /**
         * Whether the work has come to rest: the run answered and nothing
         * automated is still moving it. A run in front of a person is at rest
         * as far as this application is concerned — waiting longer will not
         * change it, somebody has to.
         */
        public boolean settled() {
            String state = state();
            return state != null && !"in-progress".equals(state) && !"on-hold".equals(state);
        }
    }

    /**
     * Asks a run how it stands.
     *
     * <p>Only the run this application asked for answers it, and on the
     * credential it asked with. A run somebody else started answers 404, the
     * same as one that does not exist — so this cannot be used to find out
     * what work a tenant has.
     *
     * @param tenant the tenant the run was started on
     * @param run    the run's id, as {@link Started#run()} gave it
     */
    public Answer answer(String tenant, String run) {
        DboWorkerProperties.Lane lane = askable(tenant);
        Supplier<String> token = tokens.get(tenant);
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(lane.getBase().toString().replaceAll("/+$", "")
                                + "/run/" + run))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token.get());
        }
        try {
            HttpResponse<String> answered =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(answered.statusCode(), answered.body());
        } catch (java.io.IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("asking '" + tenant + "' about run '" + run
                    + "' did not complete", failed);
        }
    }

    /**
     * Asks a run how it stands until it has come to rest, or until patience
     * runs out.
     *
     * <p>Polling, on purpose. The store tells nobody when a run ends — a run
     * is a record, and its end is a version of it — so the asker asks, at a
     * pace that starts quick for work that is quick and slows for work that
     * is not. Out of patience, the last answer is returned rather than an
     * exception: "still in progress" is an answer, and what to do about it is
     * the caller's decision.
     *
     * @param patience how long to keep asking
     * @return the first settled answer, the first refusal, or the last answer
     *         when patience ran out
     */
    public Answer awaiting(String tenant, String run, Duration patience) {
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

    /** The HTTP lane into a tenant, which is where work is asked for and answered. */
    private DboWorkerProperties.Lane askable(String tenant) {
        DboWorkerProperties.Lane lane = properties.getLanes().stream()
                .filter(declared -> declared.getTenant().equals(tenant))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("this application holds no lane "
                        + "into '" + tenant + "', so it has no address to ask and no credential "
                        + "to ask with; declare the lane under dbo.worker.lanes"));
        if (lane.getBase() == null) {
            // The substrate carries claims and reports, not this. A lane over
            // it is held by an enrolled participant with no route into the
            // tenant at all, which is the whole point of that plane — so the
            // honest answer is that there is nowhere to post, rather than a
            // request built against a base URI that lane never had.
            throw new IllegalArgumentException("the lane into '" + tenant + "' is over the "
                    + "substrate, which carries work already authored and offers no way to "
                    + "author any or to ask after it: a participant that starts runs names the "
                    + "tenant's base and a client to ask with, and says carrier: substrate");
        }
        return lane;
    }

    /** The one field, without a JSON parser this module does not have. */
    private static String valueOf(String body, String field) {
        String at = "\"" + field + "\":\"";
        int start = body == null ? -1 : body.indexOf(at);
        if (start < 0) {
            return null;
        }
        int from = start + at.length();
        int end = body.indexOf('"', from);
        return end < 0 ? null : body.substring(from, end);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
