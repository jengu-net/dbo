package cloud.jengu.dbo.core.api;

/**
 * A write refused by a type's declared handling, saying which rule refused it.
 *
 * <p>In the core API rather than beside the Postgres store because it is part
 * of the store's contract: every surface that writes has to be able to tell
 * this apart from a fault. Buried in a generic failure it reads as a bug in
 * the server, and the caller learns nothing about what they may do instead.
 *
 * <p>It is deliberately <b>not</b> an authorization failure. No role grants a
 * way past it — an append-only record cannot be altered by anyone, including
 * us, which is a different claim from "you lack a permission" and has to read
 * differently to whoever receives it.
 */
public class HandlingRefusedException extends RuntimeException {

    private final String typeName;
    private final String rule;

    public HandlingRefusedException(String typeName, String rule, String because) {
        super(typeName + ": refused by the " + rule + " rule — " + because);
        this.typeName = typeName;
        this.rule = rule;
    }

    /** The type whose declaration refused the write. */
    public String typeName() {
        return typeName;
    }

    /** The rule that refused it, named as the declaration names it. */
    public String rule() {
        return rule;
    }
}
