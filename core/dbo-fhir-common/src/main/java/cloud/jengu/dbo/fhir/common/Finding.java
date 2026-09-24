package cloud.jengu.dbo.fhir.common;

/**
 * One thing wrong with a document, and where.
 *
 * <p>The vocabulary a refusal is carried in, from wherever it was found to
 * whoever is told. Three answerers produce these — the toolchain, the
 * database's own checks over the expanded rows, and whatever a deployment
 * registers beside them — and a caller cannot tell which found what, because
 * the question a caller has is what to fix.
 *
 * <p><b>The path is the point.</b> It used to be folded into a sentence and
 * the sentences joined with semicolons, so a form that wanted to mark the
 * field somebody typed wrong had to parse them back out — and the one thing
 * the store knew and did not say was which element it meant. FHIR has a place
 * for it: {@code OperationOutcome.issue.expression}, one issue per finding.
 *
 * @param severity {@code error} or {@code fatal} refuse a write; {@code
 *                 warning} and {@code information} are carried and do not
 * @param path     the element, as a FHIRPath a client can act on —
 *                 {@code Patient.identifier[0].system} — or null where the
 *                 answerer genuinely does not know which element it means
 * @param key      what was checked, for somebody grouping or suppressing:
 *                 an invariant key, a rule name
 * @param detail   what to tell a person
 */
public record Finding(String severity, String path, String key, String detail) {

    /** Whether this refuses the write rather than riding along with it. */
    public boolean refuses() {
        return "error".equals(severity) || "fatal".equals(severity);
    }

    /**
     * The one-line form, kept because a log, a dead letter and a sync warning
     * all want a sentence rather than a structure.
     */
    public String says() {
        return path == null ? detail : path + ": " + detail;
    }

    /** A finding from somewhere that knows no path — a parse, a whole-document rule. */
    public static Finding about(String severity, String detail) {
        return new Finding(severity, null, null, detail);
    }
}
