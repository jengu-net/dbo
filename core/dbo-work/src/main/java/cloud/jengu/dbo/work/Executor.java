package cloud.jengu.dbo.work;

/**
 * What ran a step, named the way a run has to name it.
 *
 * <p>All four, because each answers a different question a year later: the
 * <b>name</b> what ran, the <b>version</b> which behaviour that was, the
 * <b>provider</b> whose code it was — a provider can be withdrawn — and the
 * <b>scope</b> under whose declaration it was chosen. Drop any one and per-zone
 * behaviour stops being explainable.
 */
public record Executor(String name, String version, String provider, Scope scope) {

    public Executor {
        if (name == null || version == null || provider == null || scope == null) {
            throw new IllegalArgumentException(
                    "an executor is named, versioned, provided and scoped, or it is not "
                            + "reproducible: " + name + "/" + version + "/" + provider
                            + " at " + scope);
        }
    }
}
