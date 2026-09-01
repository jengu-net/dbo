package cloud.jengu.dbo.telemetry;

/**
 * The only things a measurement may be labelled with.
 *
 * <p><b>A closed set, enforced rather than described.</b> The run envelope is
 * already the audited answer to what may be said about a piece of work without
 * saying what it was about — state, not subject — so these are its fields. But
 * the envelope was designed for a tenant reading its own runs, and a label
 * travels further than that: to a collector shared across tenants. So two of
 * the envelope's fields are deliberately absent here.
 *
 * <p><b>What is left out, and why.</b> A run's {@code key} and its
 * {@code parent} are identifiers a caller chose, so as labels they are
 * unbounded cardinality — the failure that turns a metrics store into an
 * outage. A {@code correlation} is worse: it is echoed from another system and
 * never interpreted, which is exactly right in a column a tenant queries and
 * exactly wrong as content forwarded to a shared collector, because nobody
 * here knows what is in it. Identifiers belong on a span's own id fields,
 * where a trace needs them and where they are not dimensions.
 *
 * <p>An enum rather than a string key, because the discipline that matters is
 * the one a compiler keeps. Every logging stack that ever leaked did so
 * through a field somebody was free to add.
 */
public enum Label {

    /** Whose work it was. The operator processes this by declaration. */
    TENANT("tenant"),
    /** The process a step belongs to, as the catalogue spells it. */
    PROCESS("process"),
    /** The step, bare. */
    STEP("step"),
    /** Pipeline or sweep — how the run ends, which changes what a count means. */
    KIND("kind"),
    /** Who holds it: a person, or something that claimed it. */
    HOLDER("holder"),
    /** Baseline, a zone, an organisation — where the work was resolved to. */
    SCOPE("scope"),
    /** What ran it, by name. Not its hostname; see the note on placement. */
    EXECUTOR("executor"),
    /** How it ended, as a word from a fixed vocabulary rather than a message. */
    OUTCOME("outcome");

    private final String wire;

    Label(String wire) {
        this.wire = wire;
    }

    /** The name a collector sees. */
    public String wire() {
        return wire;
    }
}
