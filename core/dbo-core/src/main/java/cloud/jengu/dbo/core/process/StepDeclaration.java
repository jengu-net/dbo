package cloud.jengu.dbo.core.process;

import java.util.Optional;
import java.util.Set;

/**
 * What a step is, before anything runs it (#71, ADR 0057 §5).
 *
 * <p><b>Manual is the baseline; automation is an attachment.</b> A step is
 * fully defined — what it reads, what it writes, what shape it consumes and
 * produces, and whether anybody else may override it — before any runner
 * exists. A step nobody has automated is not an absent step; it is one held by
 * a human, and automating it later changes the holder and nothing else.
 *
 * <p><b>Engine vocabulary, not domain vocabulary.</b> The shapes are
 * <b>opaque references a face resolves</b>: the engine can no more compare a
 * profile than name one, and the same rule that forbids {@code Task} in the
 * engine model forbids a canonical URL here from meaning anything to it. What
 * the engine does with a shape reference is hand it to the face.
 *
 * <p><b>Domains are the ones that already exist</b> — {@code r4}, {@code r5},
 * {@code identity}, {@code audit}, {@code work}. That concept already scopes
 * the change feed, so a second notion of domain would be a synonym that drifts.
 *
 * @param version     the step's own version, which a run records alongside the
 *                    executor's: reproducing last year's decision needs the
 *                    definition as well as the runner, and it is expensive to
 *                    retrofit
 * @param reads       the storage domains it reads
 * @param writes      the storage domains it writes — a step cannot be granted
 *                    more authority than its executor already holds, so this
 *                    narrows rather than widens
 * @param consumes    an opaque reference to the shape of what it takes, or
 *                    empty when it constrains nothing
 * @param produces    an opaque reference to the shape of what it makes
 * @param overridable which scope class may override it, and empty means
 *                    nobody: not overridable is the default (ADR 0059)
 * @param actions     the acts this step contains — open a run, close it,
 *                    reopen a closed one. Roles narrow <b>actions</b>, not
 *                    steps, so without these there is nothing for a role to
 *                    narrow — it would have to become a second vocabulary
 *                    maintained beside the step. And a manual step is
 *                    <i>defined</i> by them: what its human holder may do is
 *                    exactly the set an automated executor would otherwise
 *                    perform. Empty means the step has not said, not that it
 *                    admits nothing.
 */
public record StepDeclaration(StepId id, String version, Set<String> reads, Set<String> writes,
        Optional<String> consumes, Optional<String> produces, Optional<String> overridable,
        Set<String> actions) {

    public StepDeclaration {
        if (id == null || version == null || version.isBlank()) {
            throw new IllegalArgumentException(
                    "a step declares an id and a version, because a run records both");
        }
        reads = Set.copyOf(reads);
        writes = Set.copyOf(writes);
        actions = Set.copyOf(actions);
    }

    /** The smallest honest declaration: a step that reads and writes one domain. */
    public static StepDeclaration of(String id, String version, String domain) {
        return new StepDeclaration(StepId.of(id), version, Set.of(domain), Set.of(domain),
                Optional.empty(), Optional.empty(), Optional.empty(), Set.of());
    }

    public StepDeclaration consuming(String shapeReference) {
        return new StepDeclaration(id, version, reads, writes,
                Optional.of(shapeReference), produces, overridable, actions);
    }

    public StepDeclaration producing(String shapeReference) {
        return new StepDeclaration(id, version, reads, writes,
                consumes, Optional.of(shapeReference), overridable, actions);
    }

    /** Opened to a scope class, deliberately — the default is nobody (ADR 0059). */
    public StepDeclaration overridableBy(String scopeClass) {
        return new StepDeclaration(id, version, reads, writes, consumes, produces,
                Optional.of(scopeClass), actions);
    }

    /** What a holder of this step may do — declared so a role can later narrow it. */
    public StepDeclaration containing(String... actions) {
        return new StepDeclaration(id, version, reads, writes, consumes, produces,
                overridable, Set.of(actions));
    }

    /** Whether this step may write that domain at all. */
    public boolean writes(String domain) {
        return writes.contains(domain);
    }
}
