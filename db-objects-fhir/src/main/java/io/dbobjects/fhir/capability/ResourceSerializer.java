package io.dbobjects.fhir.capability;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializerFactory;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

import static io.dbobjects.fhir.capability.Resource.CONTAINED_FIELD_NAME;
import static io.dbobjects.fhir.capability.Resource.RESOURCE_TYPE_FIELD_NAME;

@Slf4j
public class ResourceSerializer extends StdSerializer<Resource> {

    public static final String DEFAULT_RESOURCE_TYPE = "unknown";

    protected ResourceSerializer() {
        super(Resource.class);
    }

    @Override
    public void serialize(Resource value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        var typeNameAnnotation = value.getClass().getAnnotation(JsonTypeName.class);
        var resourceType = DEFAULT_RESOURCE_TYPE;
        if (typeNameAnnotation == null) {
            log.warn("Every implementation of {} should be annotated with {} by specifying resource type. "
                + "For now setting the type as <{}>", Resource.class.getName(), JsonTypeName.class, resourceType);
        } else {
            resourceType = typeNameAnnotation.value();
        }
        gen.writeStringField(RESOURCE_TYPE_FIELD_NAME, resourceType);

        // Default serialization for other properties
        JavaType javaType = provider.constructType(value.getClass());
        BeanDescription beanDesc = provider.getConfig().introspect(javaType);
        @SuppressWarnings("deprecation") JsonSerializer<Object> defaultSerializer =
            BeanSerializerFactory.instance.findBeanSerializer(provider, javaType, beanDesc);
        defaultSerializer.unwrappingSerializer(null).serialize(value, gen, provider);

        if (value.getContained() != null && !value.getContained().isEmpty()) {
            log.debug("{} - contained: {}", resourceType, value.getContained());
            var containerSerializer = provider.findValueSerializer(ResourceContainer.class);
            gen.writeFieldName(CONTAINED_FIELD_NAME);
            containerSerializer.serialize(value.getContained(), gen, provider);
        } else {
            log.debug("wont write contained for {} with value {}", resourceType, value.getContained());
        }

        gen.writeEndObject();
    }

    @Override
    public void serializeWithType(Resource value, JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer) throws IOException {
        //log.info("serializing with type: {}", typeSer.getTypeInclusion());
        serialize(value, gen, serializers);
    }
}
