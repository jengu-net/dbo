package io.dbobjects.fhir.capability;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.infomas.annotation.AnnotationDetector;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

import static io.dbobjects.fhir.capability.Resource.RESOURCE_TYPE_FIELD_NAME;

@SuppressWarnings("unchecked")
@Slf4j
public class ResourceDeserializer extends StdDeserializer<Resource> {

    private static final Map<String, Class<? extends Resource>> MAPPERS = new HashMap<>();

    static {
        log.info("Scanning for Resource's ...");
        try {
            //noinspection rawtypes
            new AnnotationDetector(new AnnotationDetector.TypeReporter() {
                @SneakyThrows
                @Override
                public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
                    @SuppressWarnings("unchecked")
                    var clazz = (Class<? extends Resource>) Class.forName(className);
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


                @Override
                public Class[] annotations() {
                    return new Class[]{JsonTypeName.class};
                }
            }).detect();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected ResourceDeserializer() {
        super(Resource.class);
    }

    @Override
    public Resource deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JacksonException {
        var node = ctxt.readTree(jp);
        var typeId = ((TextNode) node.get(RESOURCE_TYPE_FIELD_NAME)).asText();
        var typeClass = MAPPERS.get(typeId);
        if (typeClass != null) {
            return jp.getCodec().treeToValue(node, typeClass);
        }
        throw new IllegalArgumentException("unrecognized resource type " + typeId);
    }

    @Override
    public Resource deserializeWithType(JsonParser jp, DeserializationContext ctxt,
                                        TypeDeserializer typeDeserializer) throws IOException {
        var node = jp.getCodec().readTree(jp);
        var typeId = ((TextNode) node.get(RESOURCE_TYPE_FIELD_NAME)).asText();
        var typeClass = MAPPERS.get(typeId);
        if (typeClass != null) {
            return (Resource) typeDeserializer.deserializeIfNatural(jp, ctxt, typeClass);
        }
        throw new IllegalArgumentException("unrecognized resource type " + typeId);
    }


}
