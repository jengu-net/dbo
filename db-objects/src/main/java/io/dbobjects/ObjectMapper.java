package io.dbobjects;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Optional;

public interface ObjectMapper {
    String serialize(Object o);

    <T> T deserialize(Class<T> clazz, String s);

    default <T> T deserialize(Class<T> clazz, byte[] bytes) {
        return deserialize(clazz, Optional.ofNullable(bytes).map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null));
    }

    <C> Collection<C> fromJsonArrayString(String entityString, Class<C> clazz);

}
