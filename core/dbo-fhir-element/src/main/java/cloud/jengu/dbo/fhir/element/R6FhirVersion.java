package cloud.jengu.dbo.fhir.element;

/**
 * FHIR R6, served from the ballot definitions this face carries.
 *
 * <p>A class per version rather than one that takes a parameter, because this
 * is how a version is <b>discovered</b>: a service declaration names a type,
 * and a type is what a container and a plain classpath can both instantiate.
 * The implementation is shared entirely — what makes this R6 is the package
 * beside it.
 */
public final class R6FhirVersion extends ElementFhirVersion {

    public R6FhirVersion() {
        super("r6");
    }
}
