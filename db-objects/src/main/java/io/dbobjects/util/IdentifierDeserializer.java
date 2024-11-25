package io.dbobjects.util;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.dbobjects.storage.StorageObjectIdentifier;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class IdentifierDeserializer extends StdDeserializer<StorageObjectIdentifier> {

    public IdentifierDeserializer() {
        this(null);
    }

    public IdentifierDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public StorageObjectIdentifier deserialize(JsonParser jp, DeserializationContext ctx) throws IOException, JacksonException {
        return StorageObjectIdentifier.fromCodeString(jp.getCodec().readValue(jp, String.class));
    }
}
