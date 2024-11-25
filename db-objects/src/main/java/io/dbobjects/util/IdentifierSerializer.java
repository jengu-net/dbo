package io.dbobjects.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.dbobjects.storage.StorageObjectIdentifier;

import java.io.IOException;

public class IdentifierSerializer extends StdSerializer<StorageObjectIdentifier> {
    public IdentifierSerializer() {
        this(null);
    }

    public IdentifierSerializer(Class<StorageObjectIdentifier> t) {
        super(t);
    }

    @Override
    public void serialize(StorageObjectIdentifier value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        if (value != null) {
            //jgen.writeStartObject();
            jgen.writeString(value.asCodeString());
            //jgen.writeEndObject();
        }
    }
}
