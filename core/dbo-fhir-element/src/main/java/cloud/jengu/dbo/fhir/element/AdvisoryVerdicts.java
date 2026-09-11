package cloud.jengu.dbo.fhir.element;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the database said about a write beside what the toolchain said, counted
 * (REQ-DBO-VAL-THE-DATABASE-ANSWER-IS-ADVISORY-UNTIL-IT-IS-NOT).
 *
 * <p>Counts and nothing else. A divergence is interesting about the checker,
 * not about the document that provoked it — and the document is a patient.
 * Writing one to a log would undo §14 in the course of measuring something
 * else, so what is kept is a tally by resource type, and the resource type is
 * a word from the specification.
 *
 * <p>Nor is any of it per request. The verdict a caller receives is the
 * toolchain's, unchanged, and these numbers exist to answer one question
 * before anything changes: on real writes, does the database say what the
 * toolchain says?
 */
final class AdvisoryVerdicts {

    /** Both said the same thing about the document: either both found something, or neither. */
    private final AtomicLong agreed = new AtomicLong();
    /** The toolchain found something and the database did not. */
    private final AtomicLong onlyTheToolchain = new AtomicLong();
    /** The database found something and the toolchain did not. */
    private final AtomicLong onlyTheDatabase = new AtomicLong();
    /** Nothing to compare: this tenant holds no expanded rows for what was written. */
    private final AtomicLong notHeld = new AtomicLong();
    /** The comparison itself failed, which is never the write's problem. */
    private final AtomicLong failed = new AtomicLong();

    /** Types that have already been said to diverge, so it is said once each. */
    private final Set<String> announced = ConcurrentHashMap.newKeySet();

    /** A reading of the tally, for whoever reports it. */
    record Tally(long agreed, long onlyTheToolchain, long onlyTheDatabase,
            long notHeld, long failed) {

        long compared() {
            return agreed + onlyTheToolchain + onlyTheDatabase;
        }
    }

    Tally tally() {
        return new Tally(agreed.get(), onlyTheToolchain.get(), onlyTheDatabase.get(),
                notHeld.get(), failed.get());
    }

    void agreed() {
        agreed.incrementAndGet();
    }

    void notHeld() {
        notHeld.incrementAndGet();
    }

    void failed() {
        failed.incrementAndGet();
    }

    /**
     * One side found something the other did not.
     *
     * @return whether this is the first time this type has diverged, so a
     *         caller can say so once rather than on every write
     */
    boolean diverged(String typeName, boolean toolchainOnly) {
        (toolchainOnly ? onlyTheToolchain : onlyTheDatabase).incrementAndGet();
        return announced.add(typeName);
    }

    /** The tally as a line, for a rollup that reports change rather than time. */
    @Override
    public String toString() {
        Tally now = tally();
        return "compared=" + now.compared() + " agreed=" + now.agreed()
                + " onlyTheToolchain=" + now.onlyTheToolchain()
                + " onlyTheDatabase=" + now.onlyTheDatabase()
                + " notHeld=" + now.notHeld() + " failed=" + now.failed();
    }
}
