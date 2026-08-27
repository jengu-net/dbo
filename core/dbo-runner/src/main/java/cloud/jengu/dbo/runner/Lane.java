package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Participation;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One tenant's lane into a runner — the participation protocol, whole, and
 * nothing else.
 *
 * <p><b>The runner must not have access to the tenant's dbo.</b> Not a
 * narrowed store handle — none. Everything it does travels as these verbs:
 * poll, claim, checkpoint, report, declare, and one read of an object the
 * work names. In-process, the host implements this over the tenant's own
 * objects ({@link #inProcess}) and the store handle stays on the host's side
 * of the line; a remote lane (the consumer's socket, ADR 0062) implements
 * the same interface over its transport, shipping the named objects with
 * the work. The runner cannot tell which it holds, and that indistinction
 * is the contract: a verb that only the in-process side could serve does
 * not belong here.
 *
 * <p><b>The runner is stateless over tenants.</b> Every tenant it serves
 * arrives as one of these; the runner holds only the task in hand and the
 * documents the task names. In an OSGi container the host registers one
 * {@code Lane} service per tenant it offers work from, and the runner's
 * activator tracks them — installing the bundle into the existing container
 * (cloud, edge, a dev embedding) is the whole deployment.
 */
public interface Lane {

    /**
     * What the identity behind this lane is entitled to work (#77).
     *
     * <p>"What a participant may claim is the intersection of its scopes and
     * what the step admits." The step's half is decided at the primitive,
     * where the declaration is; <b>this</b> half is the credential's, and only
     * the host knows the credential — which is why it is provisioned with the
     * lane rather than asked for by the runner.
     *
     * <p>There is no implicit default. {@link #everything()} is a host saying
     * it <em>is</em> the tenant and takes responsibility; {@link #ofSteps} is
     * a host bounding a participant to what its credential covers. A silent
     * "unrestricted when unset" would make the security of every remote
     * participant depend on a parameter somebody forgot.
     */
    final class Entitlement {

        private final Set<String> steps;

        private Entitlement(Set<String> steps) {
            this.steps = steps;
        }

        /** The host is the tenant: nothing is narrowed here. */
        public static Entitlement everything() {
            return new Entitlement(null);
        }

        /** Bounded to what this credential covers, and nothing else. */
        public static Entitlement ofSteps(String... steps) {
            return new Entitlement(Set.of(steps));
        }

        /**
         * Whether this entitlement covers a step, named either way.
         *
         * <p><b>A run speaks its step name bare; the catalogue speaks it
         * fully.</b> An entitlement is written in the catalogue's words —
         * {@code <module>.<process>.<step>}, because that is the id a
         * credential can name and the only one that is globally stable — but
         * {@code poll} is asked in the run's words. Comparing the two
         * literally would narrow every entitlement to nothing, which is the
         * failure this method exists to avoid: a lane that silently offers no
         * work looks exactly like a lane with no work.
         */
        public boolean covers(String step) {
            if (steps == null) {
                return true;
            }
            if (steps.contains(step)) {
                return true;
            }
            return steps.stream().anyMatch(entitled -> bare(entitled).equals(step));
        }

        /** What this lane will offer, of what was asked for. */
        Set<String> narrow(Set<String> asked) {
            if (steps == null) {
                return asked;
            }
            java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
            asked.stream().filter(this::covers).forEach(allowed::add);
            return allowed;
        }

        private static String bare(String stepId) {
            int dot = stepId.lastIndexOf('.');
            return dot > 0 ? stepId.substring(dot + 1) : stepId;
        }

        @Override
        public String toString() {
            return steps == null ? "everything" : steps.toString();
        }
    }

    /** Which tenant this lane serves — the runner's key, and its log word. */
    String tenant();

    /** Who claims and reports on this lane — name, version, provider, scope. */
    Executor identity();

    /** The work waiting for {@code steps}, from where this lane left off. */
    List<Run> poll(Set<String> steps, int limit);

    /** Takes one, or does not — the claim race is the scheduler. */
    Optional<Run> claim(Run run, Duration holdFor);

    /** Progress, which extends the claim — evidence, never a tick. */
    Run checkpoint(Run run, Map<String, Long> counts, Duration holdFor);

    /**
     * Progress that also names the milestone reached (#150). Deliberately
     * abstract, not defaulted: a default degrading this to a bare checkpoint
     * would be a step reporting where it is into a void, and a remote lane
     * that forgot to carry it would pass every test while dropping the one
     * thing the report said. Every lane decides; none inherits a drop.
     */
    Run milestone(Run run, String milestone, Map<String, Long> counts, Duration holdFor);

    /** Not done, and why — for the next taker. */
    void released(Run run, String reason);

    /** Done. */
    void closed(Run run);

    /** The tenant's housekeeping: lapsed claims handed back. Anybody may. */
    int releaseLapsed();

    /** Announces or re-announces a candidate — idempotent by key, vitals riding it. */
    void declare(Declarations.Declared declared);

    /**
     * Brings the step this participant performs into the catalogue (#147) —
     * the declaration arriving over the link instead of by installation.
     * Abstract, not defaulted (the #150 rule): a lane that quietly dropped an
     * introduction would leave the participant declaring candidacy for a
     * step the catalogue never learned. The introducer is this lane's
     * identity; an introduction grants it nothing.
     */
    void introduce(cloud.jengu.dbo.core.process.StepDeclaration step);

    /** A candidate going away for good, rather than being quiet. */
    void withdraw(Declarations.Declared declared);

    /**
     * The claimed run's inputs, resolved — and the runner's ONLY read.
     *
     * <p>The verb takes a run, never a reference, and that is the security
     * boundary (review decision): a runner cannot ask for data, relevant or
     * not — it receives what the step's own declaration entitles the run to
     * carry, resolved by the party that legitimately holds the objects. The
     * step declaration (#71) is the central profile of what a step consumes;
     * the run's inputs (#149) are instances filling those slots; joining a
     * step is agreeing to that API, automatically, because there is nothing
     * else to receive. A lane may — should — refuse a run this identity has
     * not claimed.
     *
     * <p>Empty today: a claimable run names no inputs until #149 lands, and
     * an empty map is the honest answer rather than a placeholder.
     */
    Map<String, StoredObject> inputs(Run run);

    /**
     * The in-process implementation, built and held by the HOST — the party
     * that legitimately has the tenant's objects. The runner receives the
     * interface and never the parts.
     */
    static Lane inProcess(String tenant, Runs runs, ChangeFeed feed,
            Declarations declarations, String participant, Executor identity) {
        return inProcess(tenant, runs, feed, declarations, participant, identity, null);
    }

    /**
     * The same, with the host's objects — what makes {@link #inputs} deliver
     * (#149). The store handle stays on this side of the line: the runner
     * receives resolved objects, never the handle.
     */
    static Lane inProcess(String tenant, Runs runs, ChangeFeed feed,
            Declarations declarations, String participant, Executor identity,
            cloud.jengu.dbo.core.api.ObjectStore objects) {
        return inProcess(tenant, runs, feed, declarations, participant, identity, objects,
                null);
    }

    /**
     * The same, with the catalogue's second door (#147): a service that
     * brings its own step introduces it through here. A host that wires no
     * {@code introductions} refuses an introduction loudly rather than
     * recording it nowhere.
     */
    static Lane inProcess(String tenant, Runs runs, ChangeFeed feed,
            Declarations declarations, String participant, Executor identity,
            cloud.jengu.dbo.core.api.ObjectStore objects,
            cloud.jengu.dbo.work.Introductions introductions) {
        return inProcess(tenant, runs, feed, declarations, participant, identity, objects,
                introductions, Entitlement.everything());
    }

    /**
     * The same, bounded to what this participant's credential covers (#77).
     *
     * <p>A participant with no privileges beyond its own step sees only that
     * step's work: the entitlement narrows what {@code poll} offers and
     * refuses what {@code claim} may take. Enforced here because the lane is
     * the only door a participant has, and because the credential is the
     * host's knowledge rather than the engine's.
     */
    static Lane inProcess(String tenant, Runs runs, ChangeFeed feed,
            Declarations declarations, String participant, Executor identity,
            cloud.jengu.dbo.core.api.ObjectStore objects,
            cloud.jengu.dbo.work.Introductions introductions,
            Entitlement entitlement) {
        return new Lane() {

            @Override
            public String tenant() {
                return tenant;
            }

            @Override
            public Executor identity() {
                return identity;
            }

            @Override
            public List<Run> poll(Set<String> steps, int limit) {
                // Narrowed rather than refused: a runner holding services for
                // more steps than this credential covers is a deployment
                // shape, not an attack, and it should work for the steps it
                // is entitled to instead of failing wholesale.
                Set<String> mine = entitlement.narrow(steps);
                if (mine.isEmpty()) {
                    return List.of();
                }
                return new Participation(runs, feed, participant, mine, identity)
                        .poll(limit);
            }

            @Override
            public Optional<Run> claim(Run run, Duration holdFor) {
                // Refused rather than narrowed: taking one run is a decision,
                // and a decision outside the entitlement is an error somebody
                // has to see.
                String step = run.process() + "." + run.step();
                if (!entitlement.covers(step)) {
                    throw new IllegalStateException(tenant + ": '" + identity.name()
                            + "' is not entitled to claim '" + step + "' — it holds "
                            + entitlement);
                }
                return runs.claim(run, identity, java.time.Instant.now().plus(holdFor));
            }

            @Override
            public Run checkpoint(Run run, Map<String, Long> counts, Duration holdFor) {
                return runs.checkpoint(run, counts, java.time.Instant.now().plus(holdFor));
            }

            @Override
            public Run milestone(Run run, String milestone, Map<String, Long> counts,
                    Duration holdFor) {
                return runs.milestone(run, milestone, counts,
                        java.time.Instant.now().plus(holdFor));
            }

            @Override
            public void released(Run run, String reason) {
                runs.released(run, reason);
            }

            @Override
            public void closed(Run run) {
                runs.closed(run);
            }

            @Override
            public int releaseLapsed() {
                return Participation.releaseLapsed(runs);
            }

            @Override
            public void declare(Declarations.Declared declared) {
                declarations.declare(declared);
            }

            @Override
            public void introduce(cloud.jengu.dbo.core.process.StepDeclaration step) {
                if (introductions == null) {
                    // Loud, not lost: recording it nowhere would leave the
                    // participant declaring candidacy for a step the
                    // catalogue never learned.
                    throw new IllegalStateException(tenant + ": this lane records no "
                            + "introductions, and '" + step.id() + "' was brought to it");
                }
                introductions.introduce(step, identity.name());
            }

            @Override
            public void withdraw(Declarations.Declared declared) {
                declarations.withdraw(declared);
            }

            @Override
            public Map<String, StoredObject> inputs(Run run) {
                // The read the javadoc above promises to guard: a run this
                // identity has not claimed is refused, because the claim is
                // the entitlement — not the asking.
                Run current = runs.byKey(run.key()).orElseThrow(() -> new IllegalStateException(
                        tenant + ": no run '" + run.key() + "' to read inputs of"));
                if (current.assignment() == null
                        || !identity.equals(current.assignment().executor())
                        || !current.claimed(java.time.Instant.now())) {
                    throw new IllegalStateException(tenant + ": run '" + run.key()
                            + "' is not claimed by " + identity.name()
                            + " — inputs travel with a claim, never with a question");
                }
                // Resolution is this side's act (#149): references stay
                // opaque to the engine and the runner, and only what the
                // host legitimately holds — Type/id, present here — arrives.
                // What cannot be resolved does not, which is the honest
                // answer for what is not in this store.
                Map<String, StoredObject> resolved = new java.util.LinkedHashMap<>();
                if (objects != null) {
                    current.inputs().forEach((slot, reference) -> {
                        int slash = reference.indexOf('/');
                        if (slash > 0 && reference.indexOf('/', slash + 1) < 0) {
                            objects.get(reference.substring(0, slash),
                                            reference.substring(slash + 1))
                                    .ifPresent(object -> resolved.put(slot, object));
                        }
                    });
                }
                return java.util.Collections.unmodifiableMap(resolved);
            }
        };
    }
}
