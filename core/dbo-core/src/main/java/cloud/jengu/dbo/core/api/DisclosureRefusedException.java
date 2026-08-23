package cloud.jengu.dbo.core.api;

/**
 * A read asked to reveal an identity and did not say why (#114).
 *
 * <p>Refused rather than quietly downgraded to {@link Disclosure.Mode#OMIT}: a
 * caller that asked for a whole person and received a pseudonymous one would
 * carry on as though it had what it asked for, and the record of why an
 * identity was seen — the thing the purpose exists to leave behind — would be
 * missing without anybody noticing.
 */
public final class DisclosureRefusedException extends RuntimeException {

    public DisclosureRefusedException(String typeName) {
        super("reading " + typeName + " whole is an identifying access and needs a stated "
                + "purpose — an HL7 PurposeOfUse code such as TREAT or PATRQT. Stating one is "
                + "not permission to read: it records what the access was for.");
    }
}
