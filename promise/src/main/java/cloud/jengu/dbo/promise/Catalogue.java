package cloud.jengu.dbo.promise;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an enum as a promise catalogue and names its namespace.
 *
 * <p>The namespace prefixes every constant's derived code
 * ({@code REQ-DBO} + {@code SHAPE_WRITTEN_UNDER_STAMPED} →
 * {@code REQ-DBO-SHAPE-WRITTEN-UNDER-STAMPED}), which is what keeps codes
 * globally unique when several products' catalogues are composed into one
 * graph. Promises, classifications and areas each live in their own enums,
 * so each kind carries its own namespace ({@code REQ-…}, {@code US-…},
 * {@code CON-…}) naturally.
 *
 * <p>Registration is paid at compile time: {@link CatalogueProcessor} sees
 * every annotated enum during its component's own compilation and writes its
 * name into {@link Registry#INDEX} — no classpath is ever swept, and an
 * index regenerated on every compile cannot drift or be lost.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Catalogue {

    /** The code prefix for every constant in the annotated enum. */
    String namespace();
}
