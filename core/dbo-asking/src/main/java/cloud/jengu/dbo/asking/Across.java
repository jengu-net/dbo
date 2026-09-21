package cloud.jengu.dbo.asking;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;

import java.net.URI;
import java.time.Instant;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The same questions, asked across a network.
 *
 * <p>A product integrating with a tenant it does not run has a URL and a
 * token, and this is what it holds. Everything it can ask is what the other
 * binding can ask, because both are {@link Questions} and a caller holding one
 * cannot tell which it has — which is the claim, and the reason the vocabulary
 * is declared once rather than twice.
 *
 * <p><b>What it asks for is what the tenant already serves.</b> A run is a
 * {@code Task}, a trail entry is an {@code AuditEvent}, and a record is
 * itself. No surface is added for this: the vocabulary is a way of saying
 * things a tenant already answers, so a deployment gains a way to be asked
 * without gaining a door.
 *
 * <p><b>Where the surface has no parameter for a question, it refuses.</b>
 * Answering it here, by reading everything and narrowing in this process,
 * would move a tenant's records across a network to discard most of them —
 * and would do it invisibly, which is worse than the refusal.
 */
public final class Across implements Questions {

    /** How this binding reaches the tenant: a path and search, answered whole. */
    @FunctionalInterface
    public interface Door {

        /**
         * GET this path and search against the tenant, and hand back what it
         * said.
         *
         * <p>Deliberately the whole answer as text. What a caller does with a
         * member is its own business, and a door that parsed would be this
         * module deciding a face's representation for somebody.
         */
        String get(String pathAndQuery);
    }

    private final Door door;
    private final Watching watching;

    private Across(Door door, Watching watching) {
        this.door = door;
        this.watching = watching;
    }

    /** Ask the tenant this door opens on. */
    public static Across through(Door door) {
        return new Across(door, Watching.NOBODY);
    }

    @Override
    public Across watching(Watching watching) {
        return new Across(door, watching);
    }

    @Override
    public Questions.Work work() {
        return new WorkAcross(new Asked("work", "Task", door, watching,
                java.util.List.of(), java.util.List.of()));
    }

    @Override
    public Questions.Records records(String type) {
        return new RecordsAcross(new Asked("records", type, door, watching,
                java.util.List.of(), java.util.List.of()));
    }

    @Override
    public Questions.Trail trail() {
        // AuditEvent, because that is what the tenant serves. The engine
        // calls an entry an AuditEntry and the face renders it as the
        // standard's word, which is the difference between the two bindings
        // and the only place it shows.
        return new TrailAcross(new Asked("trail", "AuditEvent", door, watching,
                java.util.List.of(), java.util.List.of()));
    }

    /**
     * One question being assembled.
     *
     * <p>Not implementing the three vocabularies, which Java refused and was
     * right to: {@code Work.by} and {@code Trail.by} share a signature and
     * differ in what they answer, and a walk of runs is not a walk of records.
     * What the three share is the ASKING — composing a search, following the
     * pages, telling a watcher — and that is what lives here.
     */
    private static final class Asked {

        private final String vocabulary;
        private final String type;
        private final Door door;
        private final Watching watching;
        private final java.util.List<String> search;
        private final java.util.List<String> named;

        Asked(String vocabulary, String type, Door door, Watching watching,
                java.util.List<String> search, java.util.List<String> named) {
            this.vocabulary = vocabulary;
            this.type = type;
            this.door = door;
            this.watching = watching;
            this.search = search;
            this.named = named;
        }

        Asked also(String name, String parameter) {
            java.util.List<String> all = new java.util.ArrayList<>(search);
            all.add(parameter);
            java.util.List<String> names = new java.util.ArrayList<>(named);
            names.add(name);
            return new Asked(vocabulary, type, door, watching, java.util.List.copyOf(all),
                    java.util.List.copyOf(names));
        }

        /**
         * Refuse a question this surface has no parameter for.
         *
         * <p>Answering it here — reading everything and narrowing in this
         * process — would move a tenant's records across a network to discard
         * most of them, and would do it invisibly, which is worse than a
         * refusal a caller can read.
         */
        <T> T refusing(String question, String why) {
            throw new UnsupportedOperationException(vocabulary + "." + question
                    + " has no parameter on this tenant's surface: " + why
                    + ". Answering it here would read everything and narrow in this process, "
                    + "which moves a tenant's records across a network to discard most of them");
        }

        private String query() {
            return search.isEmpty() ? "" : "?" + String.join("&", search);
        }

        <T> Stream<T> stream(Function<byte[], T> as) {
            long began = System.nanoTime();
            java.util.concurrent.atomic.AtomicLong produced =
                    new java.util.concurrent.atomic.AtomicLong();
            return Pages.from(door, "/" + type + query())
                    .map(as)
                    .peek(member -> produced.incrementAndGet())
                    .onClose(() -> watching.asked(new Watching.Asked(vocabulary + ".stream",
                            named, produced.get(),
                            java.time.Duration.ofNanos(System.nanoTime() - began))));
        }

        long count() {
            long began = System.nanoTime();
            String separator = search.isEmpty() ? "?" : "&";
            String asking = "/" + type + query() + separator + "_summary=count";
            long many;
            try {
                many = Bundles.total(door.get(asking));
            } catch (IllegalStateException notACount) {
                // Named, because the useful half is what was asked. A count
                // that cannot be answered is a question this tenant does not
                // serve, and the caller needs the search to see why.
                throw new IllegalStateException(vocabulary + ".count could not be answered by "
                        + asking + ": " + notACount.getMessage(), notACount);
            }
            watching.asked(new Watching.Asked(vocabulary + ".count", named, many,
                    java.time.Duration.ofNanos(System.nanoTime() - began)));
            return many;
        }

        static String encoded(String value) {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** What a screen asks about work, as the tenant serves it: a Task. */
    private record WorkAcross(Asked asked) implements Questions.Work {

        @Override
        public Questions.Work open() {
            return new WorkAcross(asked.also("open", "status:not=completed"));
        }

        @Override
        public Questions.Work heldBy(Holder holder) {
            return new WorkAcross(asked.also("heldBy", "owner=" + Asked.encoded(holder.wire())));
        }

        @Override
        public Questions.Work ofStep(String step) {
            return new WorkAcross(asked.also("ofStep", "code=" + Asked.encoded(step)));
        }

        @Override
        public Questions.Work inScope(String scope) {
            return asked.refusing("inScope", "a run's scope is engine state and the face "
                    + "renders no parameter for it");
        }

        @Override
        public Questions.Work by(String executor) {
            return asked.refusing("by", "an executor is engine state and the face renders no "
                    + "parameter for it");
        }

        @Override
        public Questions.Work correlated(String correlation) {
            return new WorkAcross(asked.also("correlated",
                    "identifier=" + Asked.encoded(correlation)));
        }

        @Override
        public Stream<Run> stream() {
            return asked.stream(bytes -> Run.of(wired(bytes)));
        }

        @Override
        public long count() {
            return asked.count();
        }
    }

    /** And about records, which the tenant serves as themselves. */
    private record RecordsAcross(Asked asked) implements Questions.Records {

        @Override
        public Questions.Records where(String path, String value) {
            return new RecordsAcross(asked.also(path,
                    Asked.encoded(path) + "=" + Asked.encoded(value)));
        }

        @Override
        public Questions.Records whereCoded(String path, String system, String code) {
            return new RecordsAcross(asked.also(path, Asked.encoded(path) + "="
                    + Asked.encoded((system == null ? "" : system + "|") + code)));
        }

        @Override
        public Questions.Records whereNot(String path, String value) {
            return new RecordsAcross(asked.also("not:" + path,
                    Asked.encoded(path) + ":not=" + Asked.encoded(value)));
        }

        @Override
        public Questions.Records newestFirst() {
            return new RecordsAcross(asked.also("newestFirst", "_sort=-_lastUpdated"));
        }

        @Override
        public Questions.Records including(String reference) {
            throw new UnsupportedOperationException(
                    "including(" + reference + ") is declared and not built: what a page does "
                            + "about a shared referent, and whether an include may hand over a "
                            + "record the caller could not ask for, are decided first");
        }

        @Override
        public Questions.Records havingAny(String type, String reference) {
            throw new UnsupportedOperationException(
                    "havingAny(" + type + ", " + reference + ") is declared and not built: the "
                            + "reverse direction is scheduled rather than missing, and every "
                            + "version's conformance report declares it out of scope by name");
        }

        @Override
        public Stream<StoredObject> stream() {
            return asked.stream(Across::wired);
        }

        @Override
        public long count() {
            return asked.count();
        }
    }

    /** And the trail, which the tenant serves as an AuditEvent. */
    private record TrailAcross(Asked asked) implements Questions.Trail {

        @Override
        public Questions.Trail about(String type, String id) {
            return new TrailAcross(asked.also("about",
                    "entity=" + Asked.encoded(type + "/" + id)));
        }

        @Override
        public Questions.Trail by(String actor) {
            return new TrailAcross(asked.also("by", "agent=" + Asked.encoded(actor)));
        }

        @Override
        public Questions.Trail underRun(String run) {
            return new TrailAcross(asked.also("underRun",
                    "entity=" + Asked.encoded("Task/" + run)));
        }

        @Override
        public Questions.Trail of(String interaction) {
            return new TrailAcross(asked.also("of", "action=" + Asked.encoded(interaction)));
        }

        @Override
        public Questions.Trail at(String appliance) {
            return asked.refusing("at", "which appliance an entry came from is carried on the "
                    + "entry and the face renders no parameter for it");
        }

        @Override
        public Questions.Trail since(Instant when) {
            return new TrailAcross(asked.also("since", "_lastUpdated=ge" + when));
        }

        @Override
        public Stream<StoredObject> stream() {
            return asked.stream(Across::wired);
        }

        @Override
        public long count() {
            return asked.count();
        }
    }

    /**
     * A member, as the store on the other side described it.
     *
     * <p>Its id and version are read off the member rather than left blank: a
     * caller holding one of these has the same record it would have held from
     * inside the deployment, and a blank id would be a difference between the
     * bindings that shows up as somebody's confusing bug rather than as a
     * missing feature.
     *
     * <p>What is absent is what the wire does not carry — when it was last
     * changed is on the member's own metadata rather than on this frame, and
     * inventing it would be worse than leaving it out.
     */
    private static StoredObject wired(byte[] payload) {
        String member = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        long version = 0;
        String said = Bundles.field(member, "versionId");
        if (!said.isEmpty()) {
            try {
                version = Long.parseLong(said);
            } catch (NumberFormatException notANumber) {
                version = 0;
            }
        }
        return new StoredObject(Bundles.field(member, "id"),
                Bundles.field(member, "resourceType"), version, null, payload, false,
                null, null, null);
    }

    /** Pages, followed by the link the tenant handed back. */
    private static final class Pages {

        private Pages() {
        }

        static Stream<byte[]> from(Door door, String first) {
            java.util.Iterator<byte[]> members = new java.util.Iterator<>() {

                private java.util.Iterator<byte[]> inThisPage =
                        java.util.Collections.emptyIterator();
                private String nextPath = first;

                @Override
                public boolean hasNext() {
                    while (!inThisPage.hasNext() && nextPath != null) {
                        String bundle = door.get(nextPath);
                        inThisPage = Bundles.members(bundle).iterator();
                        String next = Bundles.next(bundle);
                        // The link is built from the address the node binds
                        // to, and a node told to bind to everything says
                        // 0.0.0.0 — a true statement about a socket and not
                        // somewhere to dial. Only the path and search are
                        // taken, so it is followed against the door that is
                        // already open.
                        nextPath = next == null ? null : pathAndSearch(next);
                    }
                    return inThisPage.hasNext();
                }

                @Override
                public byte[] next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    return inThisPage.next();
                }
            };
            return java.util.stream.StreamSupport.stream(
                    java.util.Spliterators.spliteratorUnknownSize(members,
                            java.util.Spliterator.ORDERED), false);
        }

        private static String pathAndSearch(String link) {
            URI given = URI.create(link);
            String path = given.getRawPath();
            int fhir = path.indexOf("/fhir");
            if (fhir >= 0) {
                path = path.substring(fhir + "/fhir".length());
            }
            return path + (given.getRawQuery() == null ? "" : "?" + given.getRawQuery());
        }
    }
}
