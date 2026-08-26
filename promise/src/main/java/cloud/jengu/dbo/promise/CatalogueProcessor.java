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
@SupportedAnnotationTypes("*")
public final class CatalogueProcessor extends AbstractProcessor {

    private final Set<String> catalogues = new TreeSet<>();
    private final Set<String> proofs = new TreeSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        // The second concern (REQ-DBO-PRM-PROOFS-INDEXED-AT-COMPILE-TIME):
        // any annotation whose TYPE carries @Cites is a product's own proving
        // annotation, and every site using it is a citation. Read from the
        // mirror, so the index regenerates on every compile — a renamed test
        // method cannot leave a stale citation behind.
        for (TypeElement annotation : annotations) {
            if (annotation.getAnnotation(Cites.class) == null) {
                continue;
            }
            for (Element site : round.getElementsAnnotatedWith(annotation)) {
                indexCitation(annotation, site);
            }
        }
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
        if (round.processingOver()) {
            write(Registry.INDEX, catalogues);
            write(Proofs.INDEX, proofs);
        }
        // Never claimed: other processors may care about the same types.
        return false;
    }

    private void indexCitation(TypeElement annotationType, Element site) {
        String where = processingEnv.getElementUtils()
                .getBinaryName((TypeElement) site.getEnclosingElement())
                + "#" + site.getSimpleName();
        for (javax.lang.model.element.AnnotationMirror mirror : site.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().asElement().equals(annotationType)) {
                continue;
            }
            for (var entry : mirror.getElementValues().entrySet()) {
                if (!"value".contentEquals(entry.getKey().getSimpleName())) {
                    continue;
                }
                StringBuilder constants = new StringBuilder();
                String enumName = null;
                Object raw = entry.getValue().getValue();
                for (Object value : raw instanceof java.util.List<?> list
                        ? list : java.util.List.of(raw)) {
                    Object inner = value instanceof javax.lang.model.element.AnnotationValue av
                            ? av.getValue() : value;
                    if (inner instanceof javax.lang.model.element.VariableElement constant) {
                        enumName = processingEnv.getElementUtils()
                                .getBinaryName((TypeElement) constant.getEnclosingElement())
                                .toString();
                        if (constants.length() > 0) {
                            constants.append(',');
                        }
                        constants.append(constant.getSimpleName());
                    }
                }
                if (enumName != null) {
                    proofs.add(where + "=" + enumName + ":" + constants);
                }
            }
        }
    }

    private void write(String resource, Set<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        try (Writer out = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", resource)
                .openWriter()) {
            for (String line : lines) {
                out.write(line);
                out.write('\n');
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "could not write " + resource + ": " + e.getMessage());
        }
    }
}
