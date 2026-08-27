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

    /** Not done, and why — for the next taker. */
    void released(Run run, String reason);

    /** Done. */
    void closed(Run run);

    /** The tenant's housekeeping: lapsed claims handed back. Anybody may. */
    int releaseLapsed();

    /** Announces or re-announces a candidate — idempotent by key, vitals riding it. */
    void declare(Declarations.Declared declared);

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
                return new Participation(runs, feed, participant, steps, identity)
                        .poll(limit);
            }

            @Override
            public Optional<Run> claim(Run run, Duration holdFor) {
                return runs.claim(run, identity, java.time.Instant.now().plus(holdFor));
            }

            @Override
            public Run checkpoint(Run run, Map<String, Long> counts, Duration holdFor) {
                return runs.checkpoint(run, counts, java.time.Instant.now().plus(holdFor));
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
            public void withdraw(Declarations.Declared declared) {
                declarations.withdraw(declared);
            }

            @Override
            public Map<String, StoredObject> inputs(Run run) {
                // #149 gives a run its slot-shaped inputs; until then a
                // claimable run names none, and none is what arrives.
                return Map.of();
            }
        };
    }
}
