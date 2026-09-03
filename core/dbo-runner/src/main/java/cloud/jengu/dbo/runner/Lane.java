package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Participation;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Trackable;
import cloud.jengu.dbo.work.Trackables;

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
 * of the line; a remote lane (the consumer's socket) implements
 * the same interface over its transport, shipping the named objects with
 * the work. The runner cannot tell which it holds, and that indistinction
 * is the contract: a verb that only the in-process side could serve does
 * not belong here.
 *
 * <p><b>A refusal and a store that did not answer are different, and the
 * difference is on the exception</b> (§7.9). Everything a verb throws is
 * settled — the identity did not claim that run, the step was not granted,
 * the action was never declared — <em>except</em>
 * {@link cloud.jengu.dbo.core.api.StoreUnreachableException}, which says the
 * far side never spoke. The two want opposite recoveries: stop asking, or ask
 * again. A caller that cannot tell them apart backs off from work it is
 * entitled to and, because a claim it is holding lapses on the deadline,
 * loses that work to somebody else while nothing was actually wrong. So a
 * remote lane raises the second and no other kind, and an in-process lane
 * hands the host's own store failure through unchanged — the host holds that
 * store and is the one party that can say what its failure meant.
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
     * What the identity behind this lane is entitled to work.
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
        private final Set<String> supervised;

        private Entitlement(Set<String> steps, Set<String> supervised) {
            this.steps = steps;
            this.supervised = supervised;
        }

        /**
         * The host is the tenant: nothing is narrowed here.
         *
         * <p><b>It supervises nothing.</b> Undoing a judgment somebody made
         * about work is never a consequence of being entitled to perform it —
         * the same rule that keeps a broad write grant from erasing a person.
         * A host that may also overturn a closure says so with
         * {@link #supervising} or {@link #supervisingEverything}.
         */
        public static Entitlement everything() {
            return new Entitlement(null, Set.of());
        }

        /** Bounded to what this credential covers, and nothing else. */
        public static Entitlement ofSteps(String... steps) {
            return new Entitlement(Set.of(steps), Set.of());
        }

        /** The same reach, also supervising the steps named. */
        public Entitlement supervising(String... steps) {
            return new Entitlement(this.steps, Set.of(steps));
        }

        /** The same reach, supervising every step — a bare supervisory scope. */
        public Entitlement supervisingEverything() {
            return new Entitlement(this.steps, null);
        }

        /**
         * Whether this entitlement may undo a judgment about a step, named
         * either way — the credential's half of a supervisory act, meeting
         * the step's declared {@code reopen} action as the other half.
         */
        public boolean supervises(String step) {
            if (supervised == null) {
                return true;
            }
            if (supervised.contains(step)) {
                return true;
            }
            return supervised.stream().anyMatch(entitled -> bare(entitled).equals(step));
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

        /**
         * This entitlement bounded to the steps asked for — <b>both halves</b>,
         * each filtered by what the credential already covers, so an ask can
         * only ever narrow.
         *
         * <p>Both, because a lane bounded to some steps is bounded for every
         * purpose: keeping the supervisory half whole while narrowing the
         * working one would let an ask that reads as "this lane is for these
         * steps" leave a reach nobody asked to keep, and dropping it entirely
         * would take away a credential's grant on a field that says it
         * narrows work.
         */
        public Entitlement narrowedTo(java.util.Collection<String> asked) {
            return new Entitlement(
                    asked.stream().filter(this::covers)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    asked.stream().filter(this::supervises)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
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
            return "work=" + (steps == null ? "everything" : steps.toString())
                    + " supervise=" + (supervised == null ? "everything" : supervised.toString());
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
     * Progress that also names the milestone reached. Deliberately
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

    /**
     * Done, committing to the head of the run's chain. The result is the
     * chain's last link and always was: the store walks the chain when the
     * result lands and refuses a completion with a hole, naming the missing
     * link, so the run stays owed rather than closed on its own word. A
     * participant that signs its links closes this way; one that holds no
     * key closes with none and the store checks what it has.
     */
    void closed(Run run, String head);

    /**
     * A closed run, deliberately open again, with the reason recorded
     * (REQ-DBO-PROC-CLOSED-CAN-BE-REOPENED).
     *
     * <p><b>The supervisory verb, and the only one.</b> Every other verb here
     * is a participant acting on work it holds; this one overturns a judgment
     * somebody else already made, so it is the one act reached by the
     * supervisory half of an entitlement rather than the working half. A
     * credential that performs a step does not thereby overturn its closures,
     * and a supervisor performs no work.
     *
     * <p>Both halves must admit it: the entitlement names the step, and the
     * step declares the {@code reopen} action. A step whose declaration omits
     * it has said its closures are final, and says so by name rather than
     * quietly doing nothing.
     *
     * <p>Abstract, not defaulted, like the other verbs whose silent loss
     * would be invisible: a lane that dropped a reopen would leave an
     * operator looking at a run they were told is claimable and nobody can
     * claim.
     */
    void reopen(Run run, String because);

    /** The tenant's housekeeping: lapsed claims handed back. Anybody may. */
    int releaseLapsed();

    /** Announces or re-announces a candidate — idempotent by key, vitals riding it. */
    void declare(Declarations.Declared declared);

    /**
     * Brings the step this participant performs into the catalogue —
     * the declaration arriving over the link instead of by installation.
     * Abstract, not defaulted: a lane that quietly dropped an
     * introduction would leave the participant declaring candidacy for a
     * step the catalogue never learned. The introducer is this lane's
     * identity; an introduction grants it nothing.
     */
    void introduce(cloud.jengu.dbo.core.process.StepDeclaration step);

    /** A candidate going away for good, rather than being quiet. */
    void withdraw(Declarations.Declared declared);

    /**
     * What this participant can see behind it — the routed tree, and
     * the counterpart to {@link #declare}: one says what it can do, this says
     * what it can reach.
     *
     * <p><b>The observer is stamped here, never sent.</b> The reporter is this
     * lane's participant, which the host already holds, so a router cannot
     * name another one — an attestation its reporter could forge would not be
     * an attestation. Anything the caller put in {@code attested} is
     * discarded rather than trusted.
     *
     * <p><b>Why a verb rather than vitals.</b> Vitals ride a declaration, and
     * a declaration is keyed per step, scope and name — so a connector that
     * declared candidacy for two steps would carry the same fleet twice, and
     * withdrawing one of them would drop half of it. A routed tree is not per
     * step. The alternative that kept the transport unchanged asked the engine
     * to read inside a block it promised to treat as opaque, which is the
     * contract this rests on.
     *
     * <p>Abstract, not defaulted: a lane that quietly
     * dropped a report would leave an operator reading a fleet that stopped
     * changing for no visible reason.
     */
    void routes(List<Trackable> behind);

    /**
     * The claimed run's inputs, resolved — and the runner's ONLY read.
     *
     * <p>The verb takes a run, never a reference, and that is the security
     * boundary (review decision): a runner cannot ask for data, relevant or
     * not — it receives what the step's own declaration entitles the run to
     * carry, resolved by the party that legitimately holds the objects. The
     * step declaration is the central profile of what a step consumes;
     * the run's inputs are instances filling those slots; joining a
     * step is agreeing to that API, automatically, because there is nothing
     * else to receive. A lane may — should — refuse a run this identity has
     * not claimed.
     *
     * <p>Empty today: a claimable run names no inputs yet, and
     * an empty map is the honest answer rather than a placeholder.
     */
    Map<String, StoredObject> inputs(Run run);

    /**
     * The same inputs as work leaves the tenant: a manifest anybody carrying
     * it may read, and each document sealed under a key of its own, wrapped
     * to this identity's enrolment key. What a participant that offered a
     * key gets instead of {@link #inputs}, so a carrier between the two
     * holds the manifest and nothing readable. Refused, by name, for an
     * identity that offered no key — a seal to nobody is not a seal.
     */
    cloud.jengu.dbo.work.SealedWork sealed(Run run);

    /**
     * The same, sealed to the participants named — this identity, or things
     * it has declared behind it. This is the router's verb: a router holds
     * the claim on work it cannot read and names its routee as the recipient,
     * so what it forwards is sealed past it. Naming a routee is handing the
     * work on, and leaves the travel link that makes the routee the next
     * author; naming something not declared behind this participant, or a
     * routee that offered no key, is refused by name.
     */
    cloud.jengu.dbo.work.SealedWork sealed(Run run, List<String> recipients);

    /**
     * This identity opened one sealed document of the run, with the key it
     * holds. Reported from where the key is used, because that is the only
     * place the opening is a fact; lands on the document as its access
     * entry, with the run as the occasion.
     */
    String opened(Run run, String reference, cloud.jengu.dbo.work.RunChain.Link link);

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
     *. The store handle stays on this side of the line: the runner
     * receives resolved objects, never the handle.
     */
    static Lane inProcess(String tenant, Runs runs, ChangeFeed feed,
            Declarations declarations, String participant, Executor identity,
            cloud.jengu.dbo.core.api.ObjectStore objects) {
        return inProcess(tenant, runs, feed, declarations, participant, identity, objects,
                null);
    }

    /**
     * The same, with the catalogue's second door: a service that
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
     * The same, bounded to what this participant's credential covers.
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
        return inProcess(tenant, runs, feed, declarations, participant, identity, objects,
                introductions, entitlement, null);
    }

    /**
     * Where a hop is written down.
     *
     * <p>A hop is a travel entry about the <em>task</em> — who handed the work
     * to whom — and it is deliberately not a reading: it says the work moved,
     * not that anybody looked at it. The lane knows the hop happened; where
     * the entry lands is the host's business, because the host holds the trail.
     */
    interface Trail {
        /** A hop, with its link: the store hands the run to a participant. */
        void handedTo(Run run, String participant, cloud.jengu.dbo.work.RunChain.Link link);

        /**
         * A participant opened one sealed document of a run, where its key
         * is. The access entry: on the DOCUMENT, naming the run as its
         * occasion — the place every other reading of that document lands —
         * carrying the link the participant computed and signed.
         */
        void opened(Run run, String participant, String typeName, String id,
                cloud.jengu.dbo.work.RunChain.Link link);

        /** The run's chain as the trail holds it, in any order; the links say their order. */
        List<cloud.jengu.dbo.work.RunChain.Link> links(Run run);
    }

    /**
     * What a host knows about its participants' enrolment keys — the public
     * half each offered when it enrolled. What payload data keys are wrapped
     * to; a participant that offered none is sealed to by nobody, and a host
     * that wires none seals nothing.
     */
    interface Keys {
        /** The key a participant is sealed to. */
        Optional<cloud.jengu.dbo.core.api.seal.ParticipantKey> of(String participant);

        /** The key a participant signs its links with. */
        Optional<cloud.jengu.dbo.core.api.seal.SigningKey> signing(String participant);
    }

    /**
     * The same, able to record what a participant routes.
     *
     * <p>Wired where the tenant's own records are, for the same reason
     * {@code introductions} is: a host that wires none refuses a report
     * loudly rather than accepting one it will drop.
     */
    static Lane inProcess(String tenant, Runs runs, ChangeFeed feed,
            Declarations declarations, String participant, Executor identity,
            cloud.jengu.dbo.core.api.ObjectStore objects,
            cloud.jengu.dbo.work.Introductions introductions,
            Entitlement entitlement, Trackables trackables) {
        return inProcess(tenant, runs, feed, declarations, participant, identity, objects,
                introductions, entitlement, trackables, null);
    }

    /**
     * The same, with a trail to write hops into. A host that wires none
     * records no travel, which is honest for a host with no trail — an
     * embedded runner over a bare store — and wrong for a tenant, which is why
     * the tenant wires one.
     */
    static Lane inProcess(String tenant, Runs runs, ChangeFeed feed,
            Declarations declarations, String participant, Executor identity,
            cloud.jengu.dbo.core.api.ObjectStore objects,
            cloud.jengu.dbo.work.Introductions introductions,
            Entitlement entitlement, Trackables trackables, Trail trail) {
        return inProcess(tenant, runs, feed, declarations, participant, identity, objects,
                introductions, entitlement, trackables, trail, null);
    }

    /**
     * The same, knowing its participants' enrolment keys, so a run's inputs
     * can leave sealed. A host that wires none serves work in the clear to
     * every asker, which is right for a tenant's own machinery and is what
     * every host did before there was anything to seal to.
     */
    static Lane inProcess(String tenant, Runs runs, ChangeFeed feed,
            Declarations declarations, String participant, Executor identity,
            cloud.jengu.dbo.core.api.ObjectStore objects,
            cloud.jengu.dbo.work.Introductions introductions,
            Entitlement entitlement, Trackables trackables, Trail trail, Keys keys) {
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
                // Against the run as the STORE has it, never as the asker
                // described it. A body crosses this lane, so the process and
                // step in it are the caller's words, and a credential bounded
                // to one step could otherwise reach another's work simply by
                // naming it wrongly — the primitive re-reads before it writes,
                // so the lie would pass the check and the claim would land on
                // the real run.
                Run stored = runs.byKey(run.key()).orElse(null);
                if (stored == null) {
                    return Optional.empty();
                }
                // Refused rather than narrowed: taking one run is a decision,
                // and a decision outside the entitlement is an error somebody
                // has to see.
                String step = stored.process() + "." + stored.step();
                if (!entitlement.covers(step)) {
                    throw new IllegalStateException(tenant + ": '" + identity.name()
                            + "' is not entitled to claim '" + step + "' — it holds "
                            + entitlement);
                }
                Optional<Run> claimed =
                        runs.claim(stored, identity, java.time.Instant.now().plus(holdFor));
                // The hop. Taking the work is the store handing it to this
                // participant, and that is a fact about the task's journey —
                // recorded as travel, never as a reading, because nothing has
                // been looked at yet.
                if (claimed.isPresent() && trail != null) {
                    // The link: the store knows who claimed, so the store
                    // makes this one, committing to what the trail holds.
                    Run taken = claimed.get();
                    String previous = head(taken);
                    trail.handedTo(taken, identity.name(), new cloud.jengu.dbo.work.RunChain.Link(
                            "travel", previous,
                            cloud.jengu.dbo.work.RunChain.travelLink(previous, taken.key(),
                                    identity.name()),
                            identity.name(), identity.name(), null));
                }
                return claimed;
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
                closed(run, null);
            }

            @Override
            public void closed(Run run, String head) {
                Run current = runs.byKey(run.key()).orElse(run);
                if (trail != null) {
                    cloud.jengu.dbo.work.RunChain.Verdict verdict =
                            cloud.jengu.dbo.work.RunChain.verify(current, trail.links(current));
                    if (!verdict.complete()) {
                        // Told which link, so what gets investigated is a
                        // named run and a named hole rather than a suspicion.
                        throw new IllegalStateException(tenant + ": run '" + current.key()
                                + "' cannot close — its chain commits to link '"
                                + verdict.missingBefore() + "' the store never received");
                    }
                    boolean signs = keys != null && keys.signing(identity.name()).isPresent();
                    if (head == null && signs) {
                        throw new IllegalStateException(tenant + ": '" + identity.name()
                                + "' signs its links and closes with the head it commits to, "
                                + "and this result carries none");
                    }
                    if (head != null && !head.equals(verdict.head())) {
                        throw new IllegalStateException(tenant + ": run '" + current.key()
                                + "' cannot close — the result commits to head '" + head
                                + "' and the trail's head is '" + verdict.head() + "'");
                    }
                }
                runs.closed(run);
            }

            @Override
            public void reopen(Run run, String because) {
                // The run as the STORE has it, for both halves of what
                // follows: the asker says which run, and nothing else about
                // it. Believing the body's process and step would let a
                // credential bounded to one step reopen another's work by
                // naming it wrongly, and writing from the body would let a
                // reopening rewrite the envelope it reopens.
                Run stored = runs.byKey(run.key()).orElseThrow(
                        () -> new IllegalStateException(tenant + ": no run '" + run.key()
                                + "' to reopen"));
                // Refused rather than narrowed, for the reason a claim is:
                // overturning one closure is a decision, and a decision
                // outside the entitlement is an error somebody has to see.
                String step = stored.process() + "." + stored.step();
                if (!entitlement.supervises(step)) {
                    throw new IllegalStateException(tenant + ": '" + identity.name()
                            + "' is not entitled to reopen '" + step + "' — it holds "
                            + entitlement);
                }
                // The step's half. A declaration omitting reopen refuses here,
                // by name, whatever the credential says.
                runs.reopen(stored, because);
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
            public void routes(List<Trackable> behind) {
                if (trackables == null) {
                    throw new IllegalStateException(tenant + ": this lane records nothing "
                            + "routed, and '" + participant + "' reported " + behind.size()
                            + " behind it");
                }
                // The participant, not the identity: presence derives from the
                // cursor, and the cursor is the participant's. A connector's
                // own row is found under the same name it is watched by.
                trackables.routes(participant, behind);
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
                // Resolution is this side's act: references stay
                // opaque to the engine and the runner, and only what the
                // host legitimately holds — Type/id, present here — arrives.
                // What cannot be resolved does not, which is the honest
                // answer for what is not in this store.
                if (keys != null && keys.of(identity.name()).isPresent()) {
                    // What a participant holds decides how its work arrives:
                    // one that offered a key is sealed to, and its inputs
                    // never leave in the clear — not even when it asks.
                    throw new IllegalStateException(tenant + ": '" + identity.name()
                            + "' enrolled with a key, and its inputs travel sealed");
                }
                Map<String, StoredObject> resolved = new java.util.LinkedHashMap<>();
                if (objects != null) {
                    // This read IS the opening, today: the objects arrive in
                    // the clear, so resolving them is the moment the participant
                    // sees them, and the entry for it lands on each DOCUMENT
                    // with this run as its occasion. When payloads travel
                    // sealed, this read yields ciphertext and records nothing,
                    // and the opening moves to wherever the key is used.
                    String outer = cloud.jengu.dbo.core.api.Caller.run();
                    cloud.jengu.dbo.core.api.Caller.setRun(current.key());
                    try {
                        current.inputs().forEach((slot, reference) -> {
                            int slash = reference.indexOf('/');
                            if (slash > 0 && reference.indexOf('/', slash + 1) < 0) {
                                objects.get(reference.substring(0, slash),
                                                reference.substring(slash + 1))
                                        .ifPresent(object -> resolved.put(slot, object));
                            }
                        });
                    } finally {
                        if (outer == null) {
                            cloud.jengu.dbo.core.api.Caller.clearRun();
                        } else {
                            cloud.jengu.dbo.core.api.Caller.setRun(outer);
                        }
                    }
                }
                return java.util.Collections.unmodifiableMap(resolved);
            }

            @Override
            public cloud.jengu.dbo.work.SealedWork sealed(Run run) {
                return sealed(run, List.of(identity.name()));
            }

            @Override
            public cloud.jengu.dbo.work.SealedWork sealed(Run run, List<String> named) {
                Run current = claimedByThisIdentity(run);
                if (named == null || named.isEmpty()) {
                    throw new IllegalStateException(tenant + ": a payload is sealed to somebody, "
                            + "and nobody was named");
                }
                Map<String, cloud.jengu.dbo.core.api.seal.ParticipantKey> recipients =
                        new java.util.LinkedHashMap<>();
                for (String recipient : named) {
                    if (!recipient.equals(identity.name()) && !routee(recipient)) {
                        // Only what this participant has declared behind it:
                        // a router seals past itself to its own edges, and
                        // to nothing it merely knows the name of.
                        throw new IllegalStateException(tenant + ": '" + identity.name()
                                + "' has not declared '" + recipient
                                + "' behind it, and may not seal to it");
                    }
                    cloud.jengu.dbo.core.api.seal.ParticipantKey key = keys == null
                            ? null : keys.of(recipient).orElse(null);
                    if (key == null) {
                        throw new IllegalStateException(tenant + ": '" + recipient
                                + "' offered no key at enrolment; there is nothing to seal to");
                    }
                    recipients.put(recipient, key);
                }
                // Naming a routee is handing the work on. The forward is a
                // hop on the task's journey — a travel link, authored by the
                // router, naming the routee — and it is what makes the
                // routee the next author the chain expects. Once per routee
                // per run: asking for the seal again is not a second hop.
                if (trail != null) {
                    for (String recipient : recipients.keySet()) {
                        if (recipient.equals(identity.name())) {
                            continue;
                        }
                        boolean forwarded = trail.links(current).stream().anyMatch(link ->
                                "travel".equals(link.code()) && recipient.equals(link.subject()));
                        if (!forwarded) {
                            String previous = head(current);
                            trail.handedTo(current, recipient, new cloud.jengu.dbo.work.RunChain.Link(
                                    "travel", previous,
                                    cloud.jengu.dbo.work.RunChain.travelLink(previous,
                                            current.key(), recipient),
                                    identity.name(), recipient, null));
                        }
                    }
                }
                List<cloud.jengu.dbo.work.SealedPayload> payload = new java.util.ArrayList<>();
                if (objects != null) {
                    // The machinery's own read, in the carrier form, sealed
                    // before anything present can look: not a disclosure, and
                    // recorded as none. The opening is recorded where the key
                    // is used, through opened().
                    cloud.jengu.dbo.core.api.Disclosure.toSeal();
                    try {
                        current.inputs().forEach((slot, reference) -> {
                            int slash = reference.indexOf('/');
                            if (slash > 0 && reference.indexOf('/', slash + 1) < 0) {
                                objects.get(reference.substring(0, slash),
                                                reference.substring(slash + 1))
                                        .ifPresent(object -> payload.add(
                                                cloud.jengu.dbo.work.SealedPayload.seal(
                                                        slot, reference, object, recipients)));
                            }
                        });
                    } finally {
                        cloud.jengu.dbo.core.api.Disclosure.clear();
                    }
                }
                return new cloud.jengu.dbo.work.SealedWork(
                        // The step as it was declared — process and step —
                        // because that is the name a fleet routes on.
                        new cloud.jengu.dbo.work.Manifest(tenant,
                                current.process() + "." + current.step(), current.key(),
                                current.inputs(), List.copyOf(recipients.keySet()),
                                head(current)),
                        payload);
            }

            @Override
            public String opened(Run run, String reference,
                    cloud.jengu.dbo.work.RunChain.Link link) {
                Run current = claimedByThisIdentity(run);
                if (!current.inputs().containsValue(reference)) {
                    throw new IllegalStateException(tenant + ": run '" + current.key()
                            + "' names no input '" + reference + "' to have opened");
                }
                if (trail == null) {
                    // An opening nobody records is a disclosure nobody can
                    // answer for; a host with no trail may not serve sealed
                    // work, and this is where it finds out.
                    throw new IllegalStateException(tenant + ": this host keeps no trail "
                            + "to record an opening in");
                }
                // The link the participant computed, checked against what
                // the store holds: it must commit to the trail's head — a
                // link that commits to something else was made after one
                // that never came home — and it must be the link the store
                // computes from the same facts, under the participant's own
                // signature.
                // Who opened: this identity, or a routee behind it whose
                // signed link the router is carrying home. A router cannot
                // open, so an opening it forwards is its edge's, signed with
                // the edge's own key — which is what stops a router
                // manufacturing one.
                String by = link == null || link.author() == null ? identity.name() : link.author();
                if (!by.equals(identity.name()) && !routee(by)) {
                    throw new IllegalStateException(tenant + ": '" + identity.name()
                            + "' has not declared '" + by + "' behind it, and cannot report "
                            + "an opening of its");
                }
                String previous = head(current);
                if (link == null || !previous.equals(link.previous())) {
                    throw new IllegalStateException(tenant + ": run '" + current.key()
                            + "' — this opening commits to '"
                            + (link == null ? "nothing" : link.previous())
                            + "' and the trail's head is '" + previous
                            + "'; a link is missing before it");
                }
                String expected = cloud.jengu.dbo.work.RunChain.accessLink(previous,
                        current.key(), reference, by);
                if (!expected.equals(link.link())) {
                    throw new IllegalStateException(tenant + ": run '" + current.key()
                            + "' — the opening's link is not the link of what it says");
                }
                cloud.jengu.dbo.core.api.seal.SigningKey signer = keys == null
                        ? null : keys.signing(by).orElse(null);
                if (signer == null) {
                    throw new IllegalStateException(tenant + ": '" + by
                            + "' offered no signing key at enrolment; an opening is signed");
                }
                if (!signer.verifies(link.link().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8), link.signature())) {
                    throw new IllegalStateException(tenant + ": run '" + current.key()
                            + "' — the opening is not signed by '" + by + "'");
                }
                int slash = reference.indexOf('/');
                trail.opened(current, by, reference.substring(0, slash),
                        reference.substring(slash + 1),
                        new cloud.jengu.dbo.work.RunChain.Link("access", previous, link.link(),
                                by, reference, link.signature()));
                return link.link();
            }

            /** Whether this participant has declared the named thing directly behind it. */
            private boolean routee(String name) {
                return trackables != null && trackables.behind(participant).stream()
                        .anyMatch(behind -> name.equals(behind.id()));
            }

            /** The chain's head as the trail holds it; the root when the trail holds nothing. */
            private String head(Run run) {
                if (trail == null) {
                    return cloud.jengu.dbo.work.RunChain.root(run);
                }
                return cloud.jengu.dbo.work.RunChain.head(run, trail.links(run))
                        .orElseThrow(() -> new IllegalStateException(tenant + ": run '"
                                + run.key() + "' — the trail's chain already has a hole"));
            }

            /** The guard inputs() promises, shared by every verb answered against a claim. */
            private Run claimedByThisIdentity(Run run) {
                Run current = runs.byKey(run.key()).orElseThrow(() -> new IllegalStateException(
                        tenant + ": no run '" + run.key() + "'"));
                if (current.assignment() == null
                        || !identity.equals(current.assignment().executor())
                        || !current.claimed(java.time.Instant.now())) {
                    throw new IllegalStateException(tenant + ": run '" + run.key()
                            + "' is not claimed by " + identity.name()
                            + " — inputs travel with a claim, never with a question");
                }
                return current;
            }
        };
    }
}
