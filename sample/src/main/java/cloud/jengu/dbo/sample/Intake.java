package cloud.jengu.dbo.sample;

/**
 * What the hospital's own software does when somebody arrives, or when a
 * message about them does.
 *
 * <p>Two ways in, and which one you use depends on what you know. A booking
 * desk knows it is registering somebody new. A feed from another system knows
 * only a national number, and cannot know whether this person is already here.
 */
public final class Intake {

    private final Surface hospital;

    public Intake(Surface hospital) {
        this.hospital = hospital;
    }

    /**
     * Admit somebody, as a record that did not exist a moment ago.
     *
     * <p>Doing this twice for one person is refused rather than making a
     * second record or silently merging the two: the tenant declared that a
     * patient is identified by that identifier system, so a create that would
     * produce a second record for one identity is a conflict and the store
     * says so.
     *
     * <p>Which is why there is no read-before-write here, and why nothing
     * downstream needs a nightly job to find the duplicates it made anyway.
     */
    public Answer admit(String nationalNumber, String family, String given) {
        return hospital.write("Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:rl:nid","value":"%s"}],
                 "name":[{"family":"%s","given":["%s"]}]}"""
                .formatted(nationalNumber, family, given));
    }

    /**
     * Take what a message says about somebody, knowing only their number.
     *
     * <p>The same call creates them if nothing matched, so the sender does not
     * have to know whether this person is already here. That is the whole of
     * what writing against an identity buys: no lookup, no branch, and no race
     * between the lookup and the write.
     */
    public Answer whatTheMessageSays(String nationalNumber, String family, String given) {
        return hospital.changeWhere("Patient", "identifier=urn:rl:nid|" + nationalNumber, """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:rl:nid","value":"%s"}],
                 "name":[{"family":"%s","given":["%s"]}]}"""
                .formatted(nationalNumber, family, given));
    }
}
