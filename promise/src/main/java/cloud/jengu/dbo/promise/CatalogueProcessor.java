package cloud.jengu.dbo.promise;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes every {@code @Catalogue}-annotated enum's binary name into
 * {@link Registry#INDEX} at the compiling component's own compile time
 * (REQ-DBO-PRM-REGISTERED-AT-COMPILE-TIME).
 *
 * <p>Discovery is paid where the set of annotated types is already known —
 * the compiler hands them over — so no classpath is ever swept, and an index
 * regenerated on every compile cannot drift or be lost. The registry then
 * reads each catalogue whole; registration is never a side effect of class
 * loading, because the constants most likely to go untouched (gaps, planned
 * promises with no tests yet) are exactly the ones coverage exists to count.
 */
@SupportedAnnotationTypes("cloud.jengu.dbo.promise.Catalogue")
public final class CatalogueProcessor extends AbstractProcessor {

    private final Set<String> catalogues = new TreeSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element element : round.getElementsAnnotatedWith(Catalogue.class)) {
            if (element.getKind() != ElementKind.ENUM) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@Catalogue marks enums only — the constant's name is the code, and "
                                + "only an enum has constants to name", element);
                continue;
            }
            catalogues.add(processingEnv.getElementUtils()
                    .getBinaryName((TypeElement) element).toString());
        }
        if (round.processingOver() && !catalogues.isEmpty()) {
            try (Writer out = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", Registry.INDEX)
                    .openWriter()) {
                for (String name : catalogues) {
                    out.write(name);
                    out.write('\n');
                }
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "could not write " + Registry.INDEX + ": " + e.getMessage());
            }
        }
        // Never claimed: other processors may care about the same types.
        return false;
    }
}
