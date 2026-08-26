package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Cites;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cites the promises a test method proves. Typed to this store's own
 * catalogue, so a wrong constant is a compile error; recognised by the
 * framework through {@link Cites} alone. The annotation cites, never
 * defines — a promise proven by five tests has one text, in the catalogue.
 */
@Cites
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Proving {

    DboPromises[] value();
}
