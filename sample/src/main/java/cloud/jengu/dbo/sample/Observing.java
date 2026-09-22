package cloud.jengu.dbo.sample;

/**
 * Writing down something measured about somebody, and finding it again by who
 * it was about.
 *
 * <p>An observation is the ordinary shape of a record that does not stand
 * alone: it is worth nothing except as a thing said about a person. So what
 * this hands an integrator is a call that takes the person, and a call that
 * asks what belongs to them — never an edge to maintain, because the edge is
 * derived from the document and cannot disagree with it.
 */
public final class Observing {

    private final Surface hospital;

    public Observing(Surface hospital) {
        this.hospital = hospital;
    }

    /** Something measured about a person this store holds. */
    public Answer about(String patientId, String what) {
        return hospital.write("Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"%s"},
                 "subject":{"reference":"Patient/%s"}}"""
                .formatted(what, patientId));
    }

    /**
     * Something measured about somebody this store has never heard of.
     *
     * <p>Accepted, and that is the point. A sender that had its references
     * refused strips them to get its data in, which loses exactly what the
     * refusal was protecting — so the pointer is kept as written and can be
     * asked about.
     */
    public Answer aboutSomebodyElsewhere(String what) {
        return about("01a00000-0000-7000-8000-00000000dead", what);
    }

    /**
     * Something measured about whoever holds this number, leaving the store to
     * say who that is.
     *
     * <p>The reference is a question, answered once when the document is
     * written. A sender from another organisation has the number and not our
     * id, which is most senders most of the time.
     */
    public Answer aboutWhoeverHolds(String nationalNumber, String what) {
        return hospital.write("Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"%s"},
                 "subject":{"reference":"Patient?identifier=urn:rl:nid|%s"}}"""
                .formatted(what, nationalNumber));
    }

    /** What has been said about this person. */
    public Answer whatBelongsTo(String patientId) {
        return hospital.search("Observation", "subject=Patient/" + patientId);
    }
}
