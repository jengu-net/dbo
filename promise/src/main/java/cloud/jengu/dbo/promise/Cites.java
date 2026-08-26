package cloud.jengu.dbo.promise;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a product's own proving annotation
 * (REQ-DBO-PRM-CITATION-IS-TYPED).
 *
 * <p>A citation must be typed to the product's own catalogue enum — a wrong
 * constant a compile error — and the framework cannot know any product's
 * types. Those meet in exactly one construct: the product declares a
 * three-line annotation of its own,
 *
 * <pre>{@code
 * @Cites
 * @Retention(RUNTIME) @Target(METHOD)
 * public @interface Proving { MyPromises[] value(); }
 * }</pre>
 *
 * and the tooling recognises any annotation whose TYPE carries this one,
 * reading its {@code value()} reflectively. The annotation cites, never
 * defines: a promise proven by five tests has one text, in the catalogue.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface Cites {
}
