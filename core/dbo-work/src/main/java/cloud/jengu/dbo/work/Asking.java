package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Answered;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.ObjectStore;

import java.util.stream.Stream;

/**
 * What a product asks this tenant about its own work.
 *
 * <p>One instance is one tenant, and no method below takes a tenant: a
 * fleet-wide view holds several of these and asks each in turn, which is a
 * walk rather than a join and is slower on purpose.
 *
 * <p><b>The questions are methods, not searches anybody composes.</b> What
 * needs somebody, what is still open, what this executor is holding — those
 * are the sentences an operator says, and a vocabulary that answered them by
 * handing out a criteria builder would be the engine with a longer name.
 *
 * <p><b>Narrowing runs where the records are.</b> Everything here that can be
 * asked of the store is asked of the store, because the alternative — reading
 * everything and filtering here — moves a tenant's whole workload across to
 * discard most of it. Where the store cannot narrow, this says so rather than
 * pretending.
 */
public final class Asking {

    private final ObjectStore store;
    private final Watching watching;

    private Asking(ObjectStore store, Watching watching) {
        this.store = store;
        this.watching = watching;
    }

    /**
     * Ask the tenant whose store this is.
     *
     * <p>In the framework, or in a host that embedded it, this is the
     * {@code ObjectStore} the tenant registered when it came up.
     */
    public static Asking at(ObjectStore store) {
        return new Asking(store, Watching.NOBODY);
    }

    /**
     * The same vocabulary, with somebody watching what is asked of it.
     *
     * <p>Off until this is called, and what the watcher sees is the shape of
     * a question rather than the question: narrowings by name, how many came
     * back, how long it took. What it is for is counting and timing, and
     * neither needs the values.
     */
    public Asking watching(Watching watching) {
        return new Asking(store, watching);
    }

    /**
     * The records themselves, of one type.
     *
     * <p><b>Asking is not unsealing.</b> What comes back is what this store
     * hands the caller holding it, which for a tenant behind the membrane is
     * the record without its identifying elements. A screen lists, filters,
     * counts and pages without learning who anybody is; learning who somebody
     * is is a disclosure with a purpose and a run behind it, and it is not
     * here.
     */
    public Records records(String type) {
        return new Records(store, watching, type, java.util.List.of(), java.util.List.of());
    }

    /**
     * What was done here, and by whom.
     *
     * <p>Answered from the same handle as everything else because a tenant's
     * engine registers its records, its runs and its trail together: what
     * this deployment did about a tenant belongs in that tenant's own store,
     * queryable and versioned and dropped with it.
     *
     * <p>Reading the trail is itself an act the trail records, which is the
     * property that makes it worth reading.
     */
    public Trail trail() {
        return new Trail(store, watching, java.util.List.of(), java.util.List.of());
    }

    /** What this tenant has been asked to do, and what became of it. */
    public Work work() {
        return new Work(store, watching, java.util.List.of(), java.util.List.of());
    }

    /**
     * Runs, narrowed by something the store can narrow by.
     *
     * <p>Immutable: each question hands back another, so a caller can hold a
     * half-asked question and finish it two ways without the first affecting
     * the second.
     *
     * <p><b>Which is why the narrowings are kept and the criteria is built
     * each time it is asked.</b> {@link Criteria} is a builder that returns
     * itself, so a question holding one and narrowing it would narrow the
     * question it was narrowed FROM — counting a case, then counting the open
     * part of it, then counting the case again, and getting the open number
     * twice. A test asserts exactly that, because it is invisible until
     * somebody asks two questions of one subject.
     */
    public static final class Work {

        private final ObjectStore store;
        private final Watching watching;
        private final java.util.List<java.util.function.Consumer<Criteria>> narrowings;
        /** The same narrowings, by name, for whoever is watching. */
        private final java.util.List<String> named;

        private Work(ObjectStore store, Watching watching,
                java.util.List<java.util.function.Consumer<Criteria>> narrowings,
                java.util.List<String> named) {
            this.store = store;
            this.watching = watching;
            this.narrowings = narrowings;
            this.named = named;
        }

        private Work also(String name, java.util.function.Consumer<Criteria> narrowing) {
            java.util.List<java.util.function.Consumer<Criteria>> all =
                    new java.util.ArrayList<>(narrowings);
            all.add(narrowing);
            java.util.List<String> names = new java.util.ArrayList<>(named);
            names.add(name);
            return new Work(store, watching, java.util.List.copyOf(all),
                    java.util.List.copyOf(names));
        }

        /** The question as the store takes it, assembled fresh. */
        private Criteria asked() {
            Criteria criteria = Criteria.of(WorkModel.TYPE);
            narrowings.forEach(narrowing -> narrowing.accept(criteria));
            return criteria;
        }

        /**
         * Everything not finished with.
         *
         * <p>A run nobody holds is done or abandoned; everything else is
         * owed by somebody or something. Asked as a negation because that is
         * what open means here, and the store answers it.
         */
        public Work open() {
            return also("open", criteria -> criteria.notEq("holder",
                    EnvelopeValue.of(Holder.NOBODY.wire())));
        }

        /**
         * Whose it is right now.
         *
         * <p>{@link Holder#PERSON} is the one to reach for first: automation
         * exhausted or never attempted, which is the state an operator most
         * wants and the one a list of runs that worked silently omits.
         */
        public Work heldBy(Holder holder) {
            return also("heldBy", criteria -> criteria.eq("holder", EnvelopeValue.of(holder.wire())));
        }

        /** Of one step, by the code whoever performs it declared. */
        public Work ofStep(String step) {
            return also("ofStep", criteria -> criteria.eq("step", EnvelopeValue.of(step)));
        }

        /** Where the work happened. */
        public Work inScope(String scope) {
            return also("inScope", criteria -> criteria.eq("scope", EnvelopeValue.of(scope)));
        }

        /** What one executor is named on. */
        public Work by(String executor) {
            return also("by", criteria -> criteria.eq("executor", EnvelopeValue.of(executor)));
        }

        /** Everything filed under one correlation, across steps and processes. */
        public Work correlated(String correlation) {
            return also("correlated", criteria -> criteria.eq("correlation", EnvelopeValue.of(correlation)));
        }

        /**
         * The answer, walked as it is produced.
         *
         * <p>Close it. It is walking a cursor, and one abandoned half way
         * leaves the page it was in the middle of.
         */
        public Stream<Run> stream() {
            long began = System.nanoTime();
            java.util.concurrent.atomic.AtomicLong produced =
                    new java.util.concurrent.atomic.AtomicLong();
            return Answered.pagedBy(store::page, asked())
                    .map(Run::of)
                    .peek(run -> produced.incrementAndGet())
                    // Told at the close rather than at the open: a walk's
                    // length and its cost are not known until somebody has
                    // finished with it, and a caller who took twenty of a
                    // million asked a different question from one who read
                    // everything.
                    .onClose(() -> watching.asked(new Watching.Asked("work.stream", named,
                            produced.get(), java.time.Duration.ofNanos(System.nanoTime() - began))));
        }

        /**
         * How many, without fetching them.
         *
         * <p>Deliberately not {@code stream().count()}, which would bring
         * every run here to count it. A number beside a filter is the
         * ordinary case and paying for the rows to show it is not.
         */
        public long count() {
            long began = System.nanoTime();
            long many = store.count(asked());
            watching.asked(new Watching.Asked("work.count", named, many,
                    java.time.Duration.ofNanos(System.nanoTime() - began)));
            return many;
        }
    }

    /**
     * Records of one type, narrowed by something the store can narrow by.
     *
     * <p>The same discipline as the questions about work: narrowing runs
     * where the records are. {@code where} is the store's; a caller reaching
     * for {@code stream().filter(...)} has already moved the tenant across
     * the wire to discard most of it, so the answer to that is enough
     * {@code where} that nobody reaches for it.
     */
    public static final class Records {

        private final ObjectStore store;
        private final Watching watching;
        private final String type;
        private final java.util.List<java.util.function.Consumer<Criteria>> narrowings;
        private final java.util.List<String> named;

        private Records(ObjectStore store, Watching watching, String type,
                java.util.List<java.util.function.Consumer<Criteria>> narrowings,
                java.util.List<String> named) {
            this.store = store;
            this.watching = watching;
            this.type = type;
            this.narrowings = narrowings;
            this.named = named;
        }

        private Records also(String name, java.util.function.Consumer<Criteria> narrowing) {
            java.util.List<java.util.function.Consumer<Criteria>> all =
                    new java.util.ArrayList<>(narrowings);
            all.add(narrowing);
            java.util.List<String> names = new java.util.ArrayList<>(named);
            names.add(name);
            return new Records(store, watching, type, java.util.List.copyOf(all),
                    java.util.List.copyOf(names));
        }

        private Criteria asked() {
            Criteria criteria = Criteria.of(type);
            narrowings.forEach(narrowing -> narrowing.accept(criteria));
            return criteria;
        }

        /** Those carrying this value at this path. */
        public Records where(String path, String value) {
            return also(path, criteria -> criteria.eq(path, EnvelopeValue.of(value)));
        }

        /**
         * Those carrying this coded value at this path.
         *
         * <p>Its own method because a code is not a string: it is a value in
         * a system, the same digits mean different things in two of them, and
         * a vocabulary that made a caller flatten one into text would be
         * inviting the collision the system exists to prevent. A null system
         * asks about the code wherever it came from, which is what a screen
         * filtering its own vocabulary means.
         */
        public Records whereCoded(String path, String system, String code) {
            return also(path, criteria ->
                    criteria.eq(path, EnvelopeValue.token(system, code)));
        }

        /** Those NOT carrying it, which a screen asks as often as the other. */
        public Records whereNot(String path, String value) {
            return also("not:" + path, criteria -> criteria.notEq(path, EnvelopeValue.of(value)));
        }

        /** Most recently changed first, which is the order a screen shows. */
        public Records newestFirst() {
            return also("newestFirst", criteria -> criteria.sortByLastUpdated(false));
        }

        /**
         * Bring the referenced records along.
         *
         * <p><b>Declared and not built.</b> Following links is its own
         * subject — what a page does about a referent shared by fifty
         * members, what stops a lazy follow being one round trip per row,
         * and whether an include can hand over a record the caller could not
         * have asked for directly. Those are answered before this is, and
         * the shape is here so that the answer has somewhere to land and so
         * that nobody folds the three kinds of join into one method.
         */
        public Records including(String reference) {
            throw new UnsupportedOperationException(
                    "including(" + reference + ") is declared and not built: what a page does "
                            + "about a shared referent, and whether an include may hand over a "
                            + "record the caller could not ask for, are decided first. The "
                            + "item on asking the store a question holds the questions");
        }

        /**
         * Those pointed at by something else.
         *
         * <p>Declared and not built, and further from built than the one
         * above: the reverse direction is two features this store has
         * deliberately scheduled rather than written, and the conformance
         * report says so per version.
         */
        public Records havingAny(String type, String reference) {
            throw new UnsupportedOperationException(
                    "havingAny(" + type + ", " + reference + ") is declared and not built: the "
                            + "reverse direction is scheduled rather than missing, and every "
                            + "version's conformance report declares it out of scope by name");
        }

        /** The answer, walked as it is produced. Close it. */
        public Stream<cloud.jengu.dbo.core.api.StoredObject> stream() {
            long began = System.nanoTime();
            java.util.concurrent.atomic.AtomicLong produced =
                    new java.util.concurrent.atomic.AtomicLong();
            return Answered.pagedBy(store::page, asked())
                    .peek(held -> produced.incrementAndGet())
                    .onClose(() -> watching.asked(new Watching.Asked("records.stream", named,
                            produced.get(),
                            java.time.Duration.ofNanos(System.nanoTime() - began))));
        }

        /** How many, without fetching them. */
        public long count() {
            long began = System.nanoTime();
            long many = store.count(asked());
            watching.asked(new Watching.Asked("records.count", named, many,
                    java.time.Duration.ofNanos(System.nanoTime() - began)));
            return many;
        }
    }

    /**
     * The trail, narrowed by the questions somebody actually asks of it.
     *
     * <p>"Somebody looked her up" is not an answer anybody can act on.
     * "Somebody looked her up for treatment, at 03:14, under this run" is,
     * and each of those is a narrowing here.
     */
    public static final class Trail {

        /** The engine's own name for an entry. */
        private static final String TYPE = "AuditEntry";

        private final ObjectStore store;
        private final Watching watching;
        private final java.util.List<java.util.function.Consumer<Criteria>> narrowings;
        private final java.util.List<String> named;

        private Trail(ObjectStore store, Watching watching,
                java.util.List<java.util.function.Consumer<Criteria>> narrowings,
                java.util.List<String> named) {
            this.store = store;
            this.watching = watching;
            this.narrowings = narrowings;
            this.named = named;
        }

        private Trail also(String name, java.util.function.Consumer<Criteria> narrowing) {
            java.util.List<java.util.function.Consumer<Criteria>> all =
                    new java.util.ArrayList<>(narrowings);
            all.add(narrowing);
            java.util.List<String> names = new java.util.ArrayList<>(named);
            names.add(name);
            return new Trail(store, watching, java.util.List.copyOf(all),
                    java.util.List.copyOf(names));
        }

        private Criteria asked() {
            Criteria criteria = Criteria.of(TYPE);
            narrowings.forEach(narrowing -> narrowing.accept(criteria));
            return criteria;
        }

        /** What happened to one record. */
        public Trail about(String type, String id) {
            return also("about", criteria -> criteria
                    .eq("targetType", EnvelopeValue.of(type))
                    .eq("targetId", EnvelopeValue.of(id)));
        }

        /**
         * What one actor did.
         *
         * <p>The actor is stamped from the validated token rather than from
         * what the caller said about itself, which is what makes this
         * question answerable rather than merely askable.
         */
        public Trail by(String actor) {
            return also("by", criteria -> criteria.eq("actor", EnvelopeValue.of(actor)));
        }

        /** What happened under one run, which is how a journey reads back. */
        public Trail underRun(String run) {
            return also("underRun", criteria -> criteria.eq("run", EnvelopeValue.of(run)));
        }

        /** One kind of act — created, read, changed, gone. */
        public Trail of(String interaction) {
            return also("of", criteria ->
                    criteria.eq("interaction", EnvelopeValue.of(interaction)));
        }

        /**
         * Which appliance it happened on.
         *
         * <p>Only a replicated entry carries one: an entry this store wrote
         * happened here. An operator asking "on which bench" of a cloud
         * holding four appliances' trails cannot answer it from the actor.
         */
        public Trail at(String appliance) {
            return also("at", criteria ->
                    criteria.eq("appliance", EnvelopeValue.of(appliance)));
        }

        /** Since when, which is half of every question asked of a trail. */
        public Trail since(java.time.Instant when) {
            return also("since", criteria ->
                    criteria.lastUpdated(Criteria.RangeOp.GE, when));
        }

        /** The answer, walked as it is produced. Close it. */
        public Stream<cloud.jengu.dbo.core.api.StoredObject> stream() {
            long began = System.nanoTime();
            java.util.concurrent.atomic.AtomicLong produced =
                    new java.util.concurrent.atomic.AtomicLong();
            return Answered.pagedBy(store::page, asked())
                    .peek(entry -> produced.incrementAndGet())
                    .onClose(() -> watching.asked(new Watching.Asked("trail.stream", named,
                            produced.get(),
                            java.time.Duration.ofNanos(System.nanoTime() - began))));
        }

        /** How many, without fetching them. */
        public long count() {
            long began = System.nanoTime();
            long many = store.count(asked());
            watching.asked(new Watching.Asked("trail.count", named, many,
                    java.time.Duration.ofNanos(System.nanoTime() - began)));
            return many;
        }
    }
}
