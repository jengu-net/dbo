package io.dbobjects.fhir.capability;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class ResourceContainerSerializer extends StdSerializer<ResourceContainer> {

    protected ResourceContainerSerializer() {
        super(ResourceContainer.class);
    }

    public Class<ResourceContainer> handledType() {
        return ResourceContainer.class;
    }

    @Override
    public void serialize(ResourceContainer value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        value.normalizeContainedResources();
        if (!value.isEmpty()) {
            log.debug(">>>> serializing resource {}", value);
            gen.writeStartArray();
            value.forEach(r -> {
                try {
                    gen.writeObject(r);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            gen.writeEndArray();
        } else {
            gen.writeStartArray();
            gen.writeEndArray();
        }
        //serializers.findValueSerializer(Collection.class).serialize(value, gen, serializers);
    }

}
