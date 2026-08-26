package cloud.jengu.dbo.promise;

/**
 * A catalogue entry whose code IS its constant name.
 *
 * <p>The code is computed, never stored: there is no string to mistype and
 * no generator to trust, so a citation cannot drift from a declaration
 * (REQ-DBO-PRM-NAME-IS-THE-CODE).
 *
 * <p>{@code getDeclaringClass()} rather than {@code getClass()}, and that is
 * a trap with a proving test behind it: an enum constant WITH A BODY is an
 * anonymous subclass of its enum, and the subclass does not carry the
 * {@code @Catalogue} annotation.
 */
public interface Coded {

    default String code() {
        Enum<?> self = (Enum<?>) this;
        Catalogue catalogue = self.getDeclaringClass().getAnnotation(Catalogue.class);
        if (catalogue == null) {
            throw new IllegalStateException(self.getDeclaringClass().getName()
                    + " carries catalogue constants but no @Catalogue — the namespace half of "
                    + "every code is missing");
        }
        return catalogue.namespace() + "-" + self.name().replace('_', '-');
    }
}
