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
 *
 * <p><b>Read this off the enum, never off a constant's {@code getClass()}.</b>
 * A constant with a body is an anonymous subclass, so it does not carry its
 * enum's annotations and the lookup answers null. Where a constant is in hand,
 * {@code getDeclaringClass()} is the enum. It cost an hour here, and it will
 * recur anywhere something reads a marker off a constant rather than off the
 * type that declares it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Catalogue {

    /** The code prefix for every constant in the annotated enum. */
    String namespace();
}
