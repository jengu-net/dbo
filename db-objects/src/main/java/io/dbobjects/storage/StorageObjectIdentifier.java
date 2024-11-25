package io.dbobjects.storage;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.dbobjects.util.IdentifierDeserializer;
import io.dbobjects.util.IdentifierSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Each stored object may have one unique internal identifier (id) and zero or more identifiers (StorageObjectIdentifier)
 * known by other systems. Each object may have one unique identifier per system.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonSerialize(using = IdentifierSerializer.class)
@JsonDeserialize(using = IdentifierDeserializer.class)
public class StorageObjectIdentifier {
    /**
     * The namespace for the identifier value
     */
    private String system;
    /**
     * The value SHALL be unique within the defined system
     */
    private String value;

    public String asCodeString() {
        return system + "::" + value;
    }

    /**
     * convenience method for creating identifier object from code string
     *
     * @param codeString code string in form "[system]::[id]"
     * @return instance of StorageObjectIdentifier if valid code string used. Otherwise, null.
     */
    public static StorageObjectIdentifier fromCodeString(String codeString) {
        if (codeString == null) {
            return null;
        }
        String[] split = codeString.split("::", 2);
        return new StorageObjectIdentifier(split.length > 0 ? split[0] : null,
                split.length > 1 ? split[1] : null);
    }
}
