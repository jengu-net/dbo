package io.dbobjects.fhir.capability;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ResourceTypeResolver extends TypeIdResolverBase {

    private static final Map<String, Class<?>> MAPPERS = new HashMap<>();
    /*
        static {
            log.info("Scanning for Resource's ...");
            try {
                new AnnotationDetector(new AnnotationDetector.TypeReporter() {
                    @SneakyThrows
                    @Override
                    public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
                        var clazz = Class.forName(className);
                        var jsonTypeAnnotation = clazz.getAnnotation(JsonTypeName.class);
                        var isResource = Resource.class.isAssignableFrom(clazz);
                        if (jsonTypeAnnotation == null || jsonTypeAnnotation.value() == null || jsonTypeAnnotation.value().isBlank()) {
                            log.info("class {} -> ignored because missing of annotation {}", className, JsonTypeName.class.getName());
                        } else if (!isResource) {
                            log.info("class {} -> ignored because it does not implement {}", className, Resource.class.getName());
                        } else {
                            var key = jsonTypeAnnotation.value();
                            MAPPERS.put(key, clazz);
                            log.info("class {} -> mapped as resource with resourceType {}", className, key);
                        }
                    }

                    @SuppressWarnings({"rawtypes", "unchecked"})
                    @Override
                    public Class[] annotations() {
                        return new Class[]{JsonTypeName.class};
                    }
                }).detect();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    */
    private JavaType superType;

    @Override
    public void init(JavaType baseType) {
        superType = baseType;
    }

    @Override
    public String idFromValue(Object v) {
        log.info("TYPE RESOLVER: idFromValue: {}", v);
        return v.getClass().getAnnotation(JsonTypeName.class).value();
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
        log.info("TYPE RESOLVER: idFromValueAndType: {}; {}", value, suggestedType);
        return value.getClass().getAnnotation(JsonTypeName.class).value();
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        log.info("TYPE RESOLVER: getMechanism");
        return JsonTypeInfo.Id.CUSTOM;
    }

    @Override
    public JavaType typeFromId(DatabindContext context, String id) {
        log.info("TYPE RESOLVER: typeFromId: {}; {}", context, id);
        var subType = MAPPERS.get(id);
        if (subType != null) {
            return context.constructSpecializedType(superType, subType);
        }
        return superType;
    }


}
