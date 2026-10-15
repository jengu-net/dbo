package cloud.jengu.dbo.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What performing a run came to. The doctrine is enforced by shape: done
 * closes, failed releases with the reason, and there is no constructor a
 * service can use to close a run it failed.
 *
 * <p><b>Done carries the result</b>: what the step counted, and the records
 * it wants the tenant to hold. A step has no door onto the records and is not
 * given one — it says what should be written, and the tenant writes it, under
 * the run, through the same path any other write takes. So a step that
 * admits somebody answers with the person, and the tenant is where the person
 * becomes a record.
 */
public sealed interface Outcome {

    static Done done() {
        return new Done(Map.of(), List.of());
    }

    /** Done, with what was counted — the tally lands on the run's record. */
    static Done done(Map<String, Long> tally) {
        return new Done(tally, List.of());
    }

    /** Not done, and why — the run is released saying so, for the next taker. */
    static Outcome failed(String reason) {
        return new Failed(reason);
    }

    /**
     * Will not be done, and why: the run ends failed with the reason, as this
     * participant's own act, and nobody takes it again.
     */
    static Outcome refusing(String reason) {
        return new Refusing(reason);
    }

    /**
     * Done: what the step counted, and what it asks the tenant to write.
     *
     * <p>The writes are one unit. The tenant commits all of them or none, the
     * way it commits a transaction bundle, so a step whose result is a person
     * and their first encounter never leaves the person without the
     * encounter. One write may refer to another by the {@code urn:uuid} it
     * was given, and the reference resolves inside that one commit.
     *
     * <p>The runs it starts are its parts: started for the run that decided
     * them, and read as what it set going. They are checked before anything
     * is written, so a part the tenant would refuse refuses the whole result;
     * and started once the records are, so a part reads what its run wrote.
     *
     * @param tally  counts, named the step's own way
     * @param writes the records the result carries, in the order they are
     *               meant to be written; empty for a step that only decides
     * @param parts  the runs the result starts, as parts of this one
     * @param said   what the result says of the lasting work this run is a
     *               part of — a stage reached, or the work ended — or null
     */
    record Done(Map<String, Long> tally, List<Write> writes, List<Part> parts, Said said)
            implements Outcome {

        public Done {
            tally = Map.copyOf(tally);
            writes = List.copyOf(writes);
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        /** A result that says nothing of the work it is a part of. */
        public Done(Map<String, Long> tally, List<Write> writes, List<Part> parts) {
            this(tally, writes, parts, null);
        }

        /** A result that starts nothing. */
        public Done(Map<String, Long> tally, List<Write> writes) {
            this(tally, writes, List.of());
        }

        /** The same result, also carrying these records. */
        public Done writing(Write... more) {
            List<Write> all = new ArrayList<>(writes);
            all.addAll(List.of(more));
            return new Done(tally, all, parts, said);
        }

        /**
         * The same result, saying the lasting work this run is a part of has
         * reached a stage: kept as that work's current stage, and said on its
         * trail.
         */
        public Done reaching(String stage, String because) {
            return new Done(tally, writes, parts, new Said(stage,
                    said == null ? null : said.ending(), because));
        }

        /**
         * The same result, ending the lasting work this run is a part of —
         * {@code completed}, {@code failed} or {@code cancelled} — and why.
         */
        public Done ending(String status, String because) {
            return new Done(tally, writes, parts, new Said(
                    said == null ? null : said.stage(), status, because));
        }

        /**
         * The same result, also starting a run of {@code step} as a part of
         * this one.
         *
         * @param key what makes it this part, so a result given again finds
         *            the part already started; null for the result's own
         *            order among its parts
         */
        public Done starting(String step, Map<String, cloud.jengu.dbo.work.RunInitiator.Slot> inputs,
                String key) {
            return starting(step, inputs, key, null);
        }

        /**
         * The same result, also starting a run of an exchange its zone
         * declares, crossing to another tenant, as a part of this one: what
         * became of it is read under the run that started it.
         */
        public Done startingAcross(String step,
                Map<String, cloud.jengu.dbo.work.RunInitiator.Slot> inputs, String key,
                String crossingTo) {
            List<Part> all = new ArrayList<>(parts);
            all.add(new Part(step, inputs, key, null, crossingTo));
            return new Done(tally, writes, all, said);
        }

        /** The same, for one participant alone. */
        public Done starting(String step, Map<String, cloud.jengu.dbo.work.RunInitiator.Slot> inputs,
                String key, cloud.jengu.dbo.work.Run.Addressee addressee) {
            List<Part> all = new ArrayList<>(parts);
            all.add(new Part(step, inputs, key, addressee));
            return new Done(tally, writes, all, said);
        }
    }

    /**
     * What a result says of the lasting work its run is a part of.
     *
     * @param stage   the stage the work has reached, or null
     * @param ending  how the work ends — completed, failed or cancelled — or
     *                null where it goes on
     * @param because why, in words
     */
    record Said(String stage, String ending, String because) {
    }

    /**
     * A run a result starts as a part of the run that produced it.
     *
     * @param step      the step it is a run of, by its code
     * @param inputs    a slot per slot the step declares
     * @param key       its key within its step, or null for the result's own
     *                  order among its parts
     * @param addressee who alone may take it, or null for whoever holds the
     *                  step
     * @param crossingTo the tenant the part goes to, through their zone, on an
     *                   exchange the zone declares — or null for a part that
     *                   stays here
     */
    record Part(String step, Map<String, cloud.jengu.dbo.work.RunInitiator.Slot> inputs,
            String key, cloud.jengu.dbo.work.Run.Addressee addressee, String crossingTo) {

        public Part {
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        }

        /** A part that stays here. */
        public Part(String step, Map<String, cloud.jengu.dbo.work.RunInitiator.Slot> inputs,
                String key, cloud.jengu.dbo.work.Run.Addressee addressee) {
            this(step, inputs, key, addressee, null);
        }
    }

    record Failed(String reason) implements Outcome {}

    /**
     * The participant's decision that the work will not be done: no such
     * patient in the registry, a request it may not answer. Not a failure
     * that may pass, so the run is neither handed back, retried nor given to
     * people — it ends, and the asker reads why. Where the run was asked
     * across, the asker's run ends the same way.
     */
    record Refusing(String reason) implements Outcome {}

    /**
     * What a result came to when the tenant would not commit it.
     *
     * <p>The tenant's answer rather than a service's: a record its profile
     * rejects, an identity it already holds, a type the step never declared
     * it writes. The run has ENDED with the tenant's reason on it, because a
     * result refused for what it says would be refused again — which is what
     * separates this from {@link Failed}, where the next attempt may well
     * succeed. A service has no reason to return one; the runner reports it
     * when the lane answers a result this way.
     */
    record Refused(String reason) implements Outcome {}

    /**
     * What performing came to when the run stopped being this participant's
     * before it was finished: somebody acted on it — the housekeeping that
     * hands a lapsed claim back, another participant taking it.
     *
     * <p>Neither done nor failed. Nothing was reported, because the run is
     * somebody else's to finish and anything said in this participant's name
     * would undo what they did. A service has no reason to return one; the
     * runner reports it when the lane refuses a holder's verb for holding
     * nothing.
     */
    record Lost(String reason) implements Outcome {}

    /**
     * One record a result asks the tenant to write, said the way a
     * transaction bundle entry says it.
     *
     * <p>Four shapes, because there are four things a step means by "write
     * this": a record that did not exist ({@link #create}), one of several
     * that refer to each other ({@link #create(String, String)}), a change to
     * one it was handed at a version ({@link #update}), and a record found by
     * what it says rather than by an id ({@link #upsert}). Which one is not a
     * detail: a create of a person the tenant already holds is refused, and
     * an update to a version that has moved on is refused, and those
     * refusals are the point.
     *
     * @param method   {@code POST} or {@code PUT}
     * @param url      the entry's request url: a type, {@code Type/id}, or
     *                 {@code Type?search}
     * @param fullUrl  the {@code urn:uuid} other writes of this result refer
     *                 to it by, or null
     * @param ifMatch  the version an update was decided on, or null
     * @param resource the record, as JSON
     */
    record Write(String method, String url, String fullUrl, Long ifMatch, String resource) {

        public Write {
            if (resource == null || resource.isBlank()) {
                throw new IllegalArgumentException("a write carries the record it writes");
            }
        }

        /** A record that did not exist a moment ago. */
        public static Write create(String json) {
            return new Write("POST", typeOf(json), null, null, json);
        }

        /**
         * The same, named so the other writes of this result can refer to it
         * — {@code urn:uuid:…} — before the tenant has given it an id.
         */
        public static Write create(String fullUrl, String json) {
            return new Write("POST", typeOf(json), fullUrl, null, json);
        }

        /**
         * A change to a record the step was handed, refused if the record has
         * moved on since that version — so a decision made on what the record
         * said cannot silently overwrite what it says now.
         *
         * @param reference {@code Type/id}
         * @param version   the version the change was decided on
         */
        public static Write update(String reference, long version, String json) {
            return new Write("PUT", reference, null, version, json);
        }

        /**
         * A record found by what it says — an identifier, a canonical url —
         * changed if it is there and made if it is not.
         *
         * @param conditional {@code Type?search}, naming the identity the
         *                    tenant declared for that type
         */
        public static Write upsert(String conditional, String json) {
            return new Write("PUT", conditional, null, null, json);
        }

        /** The type this write names, which is what a step's declaration is checked against. */
        public String type() {
            int end = url.length();
            for (char stop : new char[] {'/', '?'}) {
                int at = url.indexOf(stop);
                if (at >= 0 && at < end) {
                    end = at;
                }
            }
            return url.substring(0, end);
        }

        /**
         * The resourceType, read without a JSON library this module does not
         * carry. The tenant reads the record properly and refuses a write
         * whose record is not the type it names, so this is a convenience for
         * the request url and never the check.
         */
        private static String typeOf(String json) {
            java.util.regex.Matcher type = java.util.regex.Pattern
                    .compile("\"resourceType\"\\s*:\\s*\"([A-Za-z]+)\"").matcher(json);
            if (!type.find()) {
                throw new IllegalArgumentException("a write carries a FHIR resource, and this "
                        + "names no resourceType");
            }
            return type.group(1);
        }
    }
}
