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
}
