package cloud.jengu.dbo.core.api;

/**
 * A search that would match on an identifying element, asked without a purpose
 * (#115).
 *
 * <p>Refused, and the refusal is the point. {@code name=Potter} is a question
 * about a person, and under the membrane the identifying elements are not in
 * the inner payload at all — so the query matches nothing and returns an empty
 * result, which reads exactly like <i>nobody here is called that</i>.
 *
 * <p>That silence is the largest hole the design could have: the one that never
 * appears in a read audit, because no read happened. An answer a caller cannot
 * distinguish from the truth is worse than a refusal they can act on.
 */
public final class IdentifyingSearchRefusedException extends RuntimeException {

    public IdentifyingSearchRefusedException(String typeName, String element) {
        super("searching " + typeName + " by " + element + " is an identifying access and "
                + "needs a stated purpose — an HL7 PurposeOfUse code such as TREAT or "
                + "PATRQT. Without one this store will not match on it, and will not "
                + "pretend the answer is empty.");
    }

    private IdentifyingSearchRefusedException(String message) {
        super(message);
    }

    /**
     * The purpose was stated and this store still cannot match on the element.
     *
     * <p>Also a refusal rather than an empty answer, and for the same reason:
     * "we hold this and cannot search it" and "nobody matches" are different
     * facts with different fixes, and only one of them is the caller's problem.
     */
    public static IdentifyingSearchRefusedException notMatchable(String typeName,
            String element) {
        return new IdentifyingSearchRefusedException("this store holds " + typeName + "."
                + element + " under the membrane and cannot match on it: only exact lookup "
                + "on the elements it indexes is supported, and a name is not one of them. "
                + "An empty result would have said nobody matches, which is a different "
                + "thing.");
    }
}
